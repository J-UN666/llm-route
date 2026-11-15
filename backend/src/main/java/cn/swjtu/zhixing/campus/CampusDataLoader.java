package cn.swjtu.zhixing.campus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * 启动时读取项目内校园来源数据与应用补充数据，构建地点候选索引。
 *
 * <p>数据来源（CAMPUS_DATA_ROOT 指向仓库根目录）：
 * <ul>
 *   <li>{@code source-data/校园数据/cleaned/official_places.json}：官方地点目录（199 条，UNDECLARED 坐标）；</li>
 *   <li>{@code source-data/校园数据/cleaned/place_aliases.json}：名称/搜索别名；</li>
 *   <li>{@code source-data/校园数据/cleaned/mvp_seed_candidates.json}：OSM 校园起点候选（无入口）；</li>
 *   <li>{@code web/data/campus.json}：规范化校门实体（保留 campus_gate_east / campus_gate_west 等稳定 ID）；</li>
 *   <li>{@code classpath:data/application-places.json}：应用层已核对补充（真实入口、城市目的地候选、数据版本）。</li>
 * </ul>
 *
 * <p>加载失败不会阻断应用启动，但地点接口会返回 500 PLACE_DATA_LOAD_FAILED 并带明确原因。</p>
 */
@Component
public class CampusDataLoader {

    private static final Logger log = LoggerFactory.getLogger(CampusDataLoader.class);

    private static final Path CAMPUS_DATA_DIR = Path.of("source-data", "校园数据");
    private static final String OFFICIAL_PLACES_FILE = "cleaned/official_places.json";
    private static final String PLACE_ALIASES_FILE = "cleaned/place_aliases.json";
    private static final String MVP_SEED_FILE = "cleaned/mvp_seed_candidates.json";
    private static final String CAMPUS_WEB_FILE = "web/data/campus.json";
    private static final String DATA_ROOT_HINT =
            "请从仓库根目录执行 export CAMPUS_DATA_ROOT=\"$PWD\" 后重启服务。";

    private static final String SEED_UNAVAILABLE_REASON =
            "来源只有 OSM 楼宇轮廓质心（WGS84，非入口），入口节点与可走路段未核实，暂不可算路。";
    private static final String GATE_UNAVAILABLE_REASON =
            "只有 OSM 校门候选节点与展示坐标（WGS84）；出校点、开放时段与人行/车行通行规则未核实，暂不可算路。";
    private static final String CITY_DISPLAY_ONLY_REASON =
            "只有未经核实的展示位置，缺少经核实的算路位置（落客点/出入口），暂不可算路。";

    /** 确定性顺序：先按 kind（CAMPUS/GATE/CITY），再按稳定 ID。 */
    private static final Comparator<PlaceCandidate> CANDIDATE_ORDER = Comparator
            .comparingInt((PlaceCandidate candidate) -> candidate.kind().ordinal())
            .thenComparing(PlaceCandidate::id);

    private final String configuredDataRoot;
    private final Resource applicationPlaces;
    private final ObjectMapper objectMapper;

    private volatile Snapshot snapshot;
    private volatile String loadErrorMessage;
    private volatile String loadedDataRoot;

    public CampusDataLoader(
            @Value("${campus.data-root:}") String configuredDataRoot,
            @Value("${campus.application-places:classpath:data/application-places.json}") Resource applicationPlaces,
            ObjectMapper objectMapper) {
        this.configuredDataRoot = configuredDataRoot;
        this.applicationPlaces = applicationPlaces;
        this.objectMapper = objectMapper;
    }

    /** 启动时构建索引；失败仅记录错误，不阻断启动。可重复调用（重新加载）。 */
    @PostConstruct
    public void load() {
        try {
            Snapshot next = buildSnapshot();
            this.snapshot = next;
            this.loadErrorMessage = null;
            log.info("地点索引加载完成：{} 个候选，dataVersion={}，CAMPUS_DATA_ROOT={}",
                    next.candidates().size(), next.dataVersion(), loadedDataRoot);
        } catch (RuntimeException ex) {
            this.snapshot = null;
            this.loadErrorMessage = describeFailure(ex);
            log.error("地点数据加载失败，/api/places 将返回 500 PLACE_DATA_LOAD_FAILED：{}", loadErrorMessage);
            log.debug("地点数据加载失败详情", ex);
        }
    }

    public List<PlaceCandidate> candidates() {
        return requireSnapshot().candidates();
    }

    public Optional<PlaceCandidate> findById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(requireSnapshot().byId().get(id.trim()));
    }

    public String dataVersion() {
        return requireSnapshot().dataVersion();
    }

    public boolean isLoaded() {
        return snapshot != null;
    }

    public String loadErrorMessage() {
        return loadErrorMessage;
    }

    // ------------------------------------------------------------------
    // 构建索引
    // ------------------------------------------------------------------

    private Snapshot buildSnapshot() {
        Path dataRoot = resolveDataRoot();
        Path campusDir = dataRoot.resolve(CAMPUS_DATA_DIR);
        Path officialPath = campusDir.resolve(OFFICIAL_PLACES_FILE);
        Path aliasesPath = campusDir.resolve(PLACE_ALIASES_FILE);
        Path seedPath = campusDir.resolve(MVP_SEED_FILE);
        Path campusWebPath = dataRoot.resolve(CAMPUS_WEB_FILE);
        requireRegularFiles(List.of(officialPath, aliasesPath, seedPath, campusWebPath));

        // 先把全部 JSON 读成树，格式错误时直接给出出错文件，避免只加载一半。
        JsonNode officialJson = readJson(officialPath);
        JsonNode aliasesJson = readJson(aliasesPath);
        JsonNode seedJson = readJson(seedPath);
        JsonNode campusWebJson = readJson(campusWebPath);
        JsonNode applicationJson = readApplicationJson();

        AppDataset application = parseApplicationData(applicationJson);
        String dataVersion = application.dataVersion();
        Map<String, List<String>> aliasesByPlace = parseAliases(aliasesJson);

        List<PlaceCandidate> baseCandidates = new ArrayList<>();
        Set<String> officialIds = new LinkedHashSet<>();
        for (JsonNode node : requireArray(officialJson, officialPath)) {
            PlaceCandidate candidate = buildOfficialPlace(node, aliasesByPlace, dataVersion);
            if (!officialIds.add(candidate.id())) {
                throw new PlaceDataLoadException("official_places.json 存在重复的 place_id: " + candidate.id());
            }
            baseCandidates.add(candidate);
        }
        warnDanglingAliases(aliasesByPlace, officialIds);

        for (JsonNode node : requireArray(seedJson, seedPath)) {
            baseCandidates.add(buildSeedCandidate(node, dataVersion));
        }

        JsonNode gates = campusWebJson.get("gates");
        if (gates == null || !gates.isArray() || gates.isEmpty()) {
            throw new PlaceDataLoadException("web/data/campus.json 缺少非空 gates 数组: " + campusWebPath);
        }
        for (JsonNode node : gates) {
            baseCandidates.add(buildGateCandidate(node, dataVersion));
        }

        LinkedHashMap<String, PlaceCandidate> built = new LinkedHashMap<>();
        for (PlaceCandidate base : baseCandidates) {
            AppPlace supplement = application.placesById().get(base.id());
            PlaceCandidate candidate = supplement == null ? base : applySupplement(base, supplement);
            putUnique(built, candidate, "基础数据或应用补充");
        }
        for (AppPlace appPlace : application.placesById().values()) {
            if (built.containsKey(appPlace.id())) {
                continue;
            }
            if (appPlace.kind() != PlaceKind.CITY) {
                throw new PlaceDataLoadException("application-places.json 引用了基础数据中不存在的地点 id: "
                        + appPlace.id() + "（kind=" + appPlace.kind() + "）");
            }
            putUnique(built, buildCityCandidate(appPlace, dataVersion), "application-places.json");
        }

        List<PlaceCandidate> ordered = built.values().stream().sorted(CANDIDATE_ORDER).toList();
        LinkedHashMap<String, PlaceCandidate> byId = new LinkedHashMap<>();
        ordered.forEach(candidate -> byId.put(candidate.id(), candidate));
        return new Snapshot(dataVersion, ordered, byId);
    }

    private PlaceCandidate buildOfficialPlace(JsonNode node, Map<String, List<String>> aliasesByPlace,
                                              String dataVersion) {
        String id = requiredText(node, "place_id", "official_places.json 条目");
        String context = "official_places.json 条目 " + id;
        String name = requiredText(node, "name", context);
        String category = optionalText(node, "category");
        Double longitude = requiredDouble(node, "source_longitude", context);
        Double latitude = requiredDouble(node, "source_latitude", context);
        String coordinateSystem = requiredText(node, "coordinate_system", context);
        String positionRole = requiredText(node, "position_role", context);
        String sourceId = optionalText(node, "source_id");
        List<String> aliases = aliasesByPlace.getOrDefault(id, List.of());
        GeoPosition displayPosition = new GeoPosition(id, longitude, latitude, coordinateSystem, positionRole);
        List<String> sourceRefs = sourceId == null ? List.of() : List.of(sourceId);
        return new PlaceCandidate(id, name, PlaceKind.CAMPUS, category, aliases, false,
                officialUnavailableReason(coordinateSystem, positionRole), "UNVERIFIED",
                displayPosition, null, dataVersion, sourceRefs);
    }

    private PlaceCandidate buildSeedCandidate(JsonNode node, String dataVersion) {
        String id = requiredText(node, "place_id", "mvp_seed_candidates.json 条目");
        String context = "mvp_seed_candidates.json 条目 " + id;
        String name = requiredText(node, "name", context);
        String nameZh = optionalText(node, "name_zh");
        String category = optionalText(node, "category");
        Double longitude = requiredDouble(node, "longitude", context);
        Double latitude = requiredDouble(node, "latitude", context);
        String coordinateSystem = requiredText(node, "coordinate_system", context);
        String positionRole = requiredText(node, "position_role", context);
        String sourceId = optionalText(node, "source_id");
        List<String> aliases = nameZh == null || nameZh.equals(name) ? List.of() : List.of(nameZh);
        GeoPosition displayPosition = new GeoPosition(id, longitude, latitude, coordinateSystem, positionRole);
        List<String> sourceRefs = sourceId == null ? List.of() : List.of(sourceId);
        return new PlaceCandidate(id, name, PlaceKind.CAMPUS, category, aliases, false,
                SEED_UNAVAILABLE_REASON, "UNVERIFIED", displayPosition, null, dataVersion, sourceRefs);
    }

    private PlaceCandidate buildGateCandidate(JsonNode node, String dataVersion) {
        String id = requiredText(node, "id", "web/data/campus.json gates 条目");
        String context = "web/data/campus.json gates 条目 " + id;
        String name = requiredText(node, "n", context);
        List<String> aliases = textList(node, "aliases", context);
        // campus.json 中校门 x=经度、y=纬度，取自 OSM 节点（WGS84）；仅作展示，不是已核实出校点。
        Double longitude = requiredDouble(node, "x", context);
        Double latitude = requiredDouble(node, "y", context);
        List<String> sourceRefs = textList(node, "sourceIds", context);
        GeoPosition displayPosition =
                new GeoPosition(id, longitude, latitude, "WGS84", "gate_display_not_verified_exit");
        return new PlaceCandidate(id, name, PlaceKind.GATE, "campus_gate", aliases, false,
                GATE_UNAVAILABLE_REASON, "UNVERIFIED", displayPosition, null, dataVersion, sourceRefs);
    }

    private PlaceCandidate buildCityCandidate(AppPlace appPlace, String dataVersion) {
        // 算路点必须由应用数据显式提供；展示位置（如未核实的楼宇质心）不能提升为算路点。
        GeoPosition routingPosition = appPlace.routingPosition();
        GeoPosition displayPosition = appPlace.displayPosition() != null
                ? appPlace.displayPosition() : routingPosition;
        if (displayPosition == null) {
            throw new PlaceDataLoadException("application-places.json 城市目的地 " + appPlace.id()
                    + " 缺少 displayPosition 与 routingPosition，无法作为候选目的地");
        }
        boolean routingReady = routingPosition != null;
        String unavailableReason = routingReady
                ? null
                : firstNonBlank(appPlace.unavailableReason(), CITY_DISPLAY_ONLY_REASON);
        String verificationStatus = appPlace.verificationStatus() == null
                ? "UNVERIFIED" : appPlace.verificationStatus();
        return new PlaceCandidate(appPlace.id(), appPlace.name(), PlaceKind.CITY, appPlace.category(),
                appPlace.aliases(), routingReady, unavailableReason, verificationStatus,
                displayPosition, routingPosition, dataVersion, appPlace.sourceRefs());
    }

    private PlaceCandidate applySupplement(PlaceCandidate base, AppPlace appPlace) {
        if (appPlace.kind() != base.kind()) {
            throw new PlaceDataLoadException("application-places.json 中 " + appPlace.id() + " 的 kind="
                    + appPlace.kind() + " 与基础数据 kind=" + base.kind() + " 不一致");
        }
        GeoPosition routingPosition = appPlace.routingPosition() != null
                ? appPlace.routingPosition() : base.routingPosition();
        GeoPosition displayPosition = appPlace.displayPosition() != null
                ? appPlace.displayPosition() : base.displayPosition();
        boolean routingReady = routingPosition != null;
        String unavailableReason = routingReady ? null : firstNonBlank(
                appPlace.unavailableReason(), base.unavailableReason(),
                "缺少经核实的算路位置（入口或出校点），暂不可算路。");
        String verificationStatus = appPlace.verificationStatus() != null
                ? appPlace.verificationStatus() : base.verificationStatus();
        return new PlaceCandidate(base.id(), base.name(), base.kind(), base.category(),
                mergeAliases(base.aliases(), appPlace.aliases(), base.name()),
                routingReady, unavailableReason, verificationStatus,
                displayPosition, routingPosition, base.dataVersion(),
                mergeSourceRefs(base.sourceRefs(), appPlace.sourceRefs()));
    }

    // ------------------------------------------------------------------
    // 应用数据解析
    // ------------------------------------------------------------------

    private AppDataset parseApplicationData(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new PlaceDataLoadException("应用地点数据根节点必须是 JSON 对象: " + describeResource());
        }
        String dataVersion = requiredText(root, "dataVersion", "application-places.json");
        JsonNode places = root.get("places");
        if (places == null || !places.isArray() || places.isEmpty()) {
            throw new PlaceDataLoadException("application-places.json 缺少非空 places 数组");
        }
        LinkedHashMap<String, AppPlace> placesById = new LinkedHashMap<>();
        for (int index = 0; index < places.size(); index++) {
            JsonNode node = places.get(index);
            String context = "application-places.json places[" + index + "]";
            if (node == null || !node.isObject()) {
                throw new PlaceDataLoadException(context + " 必须是 JSON 对象");
            }
            AppPlace appPlace = parseAppPlace(node, context);
            if (placesById.putIfAbsent(appPlace.id(), appPlace) != null) {
                throw new PlaceDataLoadException("application-places.json 存在重复地点 id: " + appPlace.id());
            }
        }
        return new AppDataset(dataVersion, placesById);
    }

    private AppPlace parseAppPlace(JsonNode node, String context) {
        String id = requiredText(node, "id", context);
        context = context + "（id=" + id + "）";
        String kindText = requiredText(node, "kind", context);
        PlaceKind kind;
        try {
            kind = PlaceKind.valueOf(kindText.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new PlaceDataLoadException(context + " 的 kind 非法: " + kindText + "（可选 CAMPUS、GATE、CITY）");
        }
        String name = optionalText(node, "name");
        if (kind == PlaceKind.CITY && (name == null || name.isBlank())) {
            throw new PlaceDataLoadException(context + " 是城市目的地，name 不能为空");
        }
        String category = optionalText(node, "category");
        List<String> aliases = mergeAliases(List.of(), textList(node, "aliases", context), name);
        String verificationStatus = optionalText(node, "verificationStatus");
        String verifiedAt = optionalText(node, "verifiedAt");
        GeoPosition displayPosition = parsePosition(node.get("displayPosition"), context + " 的 displayPosition");
        GeoPosition routingPosition = parsePosition(node.get("routingPosition"), context + " 的 routingPosition");
        String unavailableReason = optionalText(node, "unavailableReason");
        List<String> sourceRefs = textList(node, "sourceRefs", context);
        return new AppPlace(id, kind, name, category, aliases, verificationStatus, verifiedAt,
                displayPosition, routingPosition, unavailableReason, sourceRefs);
    }

    private static GeoPosition parsePosition(JsonNode node, String context) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new PlaceDataLoadException(context + " 必须是对象或 null");
        }
        String pointId = requiredText(node, "pointId", context);
        Double longitude = requiredDouble(node, "longitude", context);
        Double latitude = requiredDouble(node, "latitude", context);
        String coordinateSystem = requiredText(node, "coordinateSystem", context);
        String positionRole = requiredText(node, "positionRole", context);
        if (longitude < -180 || longitude > 180) {
            throw new PlaceDataLoadException(context + " 经度越界: " + longitude);
        }
        if (latitude < -90 || latitude > 90) {
            throw new PlaceDataLoadException(context + " 纬度越界: " + latitude);
        }
        return new GeoPosition(pointId, longitude, latitude, coordinateSystem, positionRole);
    }

    private JsonNode readApplicationJson() {
        byte[] bytes;
        try (InputStream input = applicationPlaces.getInputStream()) {
            bytes = input.readAllBytes();
        } catch (IOException ex) {
            throw new PlaceDataLoadException("应用地点数据文件缺失或不可读: " + describeResource()
                    + " — " + ex.getMessage(), ex);
        }
        try {
            JsonNode node = objectMapper.readTree(bytes);
            if (node == null || node.isNull()) {
                throw new PlaceDataLoadException("应用地点数据文件为空: " + describeResource());
            }
            return node;
        } catch (IOException ex) {
            throw new PlaceDataLoadException("应用地点数据 JSON 解析失败: " + describeResource()
                    + " — " + ex.getMessage(), ex);
        }
    }

    // ------------------------------------------------------------------
    // 文件与路径
    // ------------------------------------------------------------------

    private Path resolveDataRoot() {
        if (configuredDataRoot == null || configuredDataRoot.isBlank()) {
            throw new PlaceDataLoadException("未配置 CAMPUS_DATA_ROOT（校园数据根目录）。" + DATA_ROOT_HINT);
        }
        Path dataRoot;
        try {
            dataRoot = Paths.get(configuredDataRoot.trim()).toAbsolutePath().normalize();
        } catch (InvalidPathException ex) {
            throw new PlaceDataLoadException("CAMPUS_DATA_ROOT 不是合法路径: " + configuredDataRoot
                    + " — " + ex.getMessage(), ex);
        }
        if (!Files.isDirectory(dataRoot)) {
            throw new PlaceDataLoadException("CAMPUS_DATA_ROOT 指向的目录不存在: " + dataRoot + "。" + DATA_ROOT_HINT);
        }
        this.loadedDataRoot = dataRoot.toString();
        return dataRoot;
    }

    private static void requireRegularFiles(List<Path> paths) {
        List<String> missing = paths.stream().filter(path -> !Files.isRegularFile(path)).map(Path::toString).toList();
        if (!missing.isEmpty()) {
            throw new PlaceDataLoadException("校园地点数据文件缺失：\n  - "
                    + String.join("\n  - ", missing) + "\n" + DATA_ROOT_HINT);
        }
    }

    private JsonNode readJson(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            JsonNode node = objectMapper.readTree(input);
            if (node == null || node.isNull()) {
                throw new PlaceDataLoadException("地点数据文件为空: " + path);
            }
            return node;
        } catch (IOException ex) {
            throw new PlaceDataLoadException("地点数据 JSON 解析失败: " + path + " — " + ex.getMessage(), ex);
        }
    }

    private static JsonNode requireArray(JsonNode node, Path path) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            throw new PlaceDataLoadException("地点数据文件内容不是非空 JSON 数组: " + path);
        }
        return node;
    }

    // ------------------------------------------------------------------
    // JSON 字段读取与校验
    // ------------------------------------------------------------------

    private static String requiredText(JsonNode node, String field, String context) {
        String value = optionalText(node, field);
        if (value == null) {
            throw new PlaceDataLoadException(context + " 缺少必填文本字段 " + field);
        }
        return value;
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isValueNode()) {
            return null;
        }
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }

    private static Double requiredDouble(JsonNode node, String field, String context) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isNumber()) {
            throw new PlaceDataLoadException(context + " 缺少数值字段 " + field);
        }
        return value.asDouble();
    }

    private static List<String> textList(JsonNode node, String field, String context) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return List.of();
        }
        if (!value.isArray()) {
            throw new PlaceDataLoadException(context + " 的 " + field + " 必须是字符串数组");
        }
        LinkedHashSet<String> collected = new LinkedHashSet<>();
        for (JsonNode item : value) {
            if (item == null || !item.isTextual()) {
                throw new PlaceDataLoadException(context + " 的 " + field + " 必须是字符串数组");
            }
            String text = item.asText().trim();
            if (!text.isEmpty()) {
                collected.add(text);
            }
        }
        return List.copyOf(collected);
    }

    private static Map<String, List<String>> parseAliases(JsonNode root) {
        if (root == null || !root.isArray()) {
            throw new PlaceDataLoadException("place_aliases.json 内容必须是 JSON 数组");
        }
        LinkedHashMap<String, LinkedHashSet<String>> collected = new LinkedHashMap<>();
        for (JsonNode node : root) {
            if (node == null || !node.isObject()) {
                throw new PlaceDataLoadException("place_aliases.json 存在非对象条目");
            }
            String placeId = requiredText(node, "place_id", "place_aliases.json 条目");
            String alias = requiredText(node, "alias", "place_aliases.json 条目（place_id=" + placeId + "）");
            collected.computeIfAbsent(placeId, key -> new LinkedHashSet<>()).add(alias);
        }
        LinkedHashMap<String, List<String>> aliasesByPlace = new LinkedHashMap<>();
        collected.forEach((placeId, aliases) -> aliasesByPlace.put(placeId, List.copyOf(aliases)));
        return aliasesByPlace;
    }

    private static void warnDanglingAliases(Map<String, List<String>> aliasesByPlace, Set<String> officialIds) {
        for (String placeId : aliasesByPlace.keySet()) {
            if (!officialIds.contains(placeId)) {
                log.warn("place_aliases.json 引用了 official_places.json 中不存在的地点 id: {}", placeId);
            }
        }
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static void putUnique(Map<String, PlaceCandidate> target, PlaceCandidate candidate, String origin) {
        PlaceCandidate previous = target.putIfAbsent(candidate.id(), candidate);
        if (previous != null) {
            throw new PlaceDataLoadException("地点稳定 ID 重复: " + candidate.id() + "（" + origin + "）");
        }
    }

    private static String officialUnavailableReason(String coordinateSystem, String positionRole) {
        String coordinateClue = "UNDECLARED".equalsIgnoreCase(coordinateSystem)
                ? "坐标为 UNDECLARED 坐标系、位置角色 " + positionRole + "（非入口），确认坐标系前只作名称线索"
                : "坐标系为 " + coordinateSystem + "、位置角色 " + positionRole + "（非入口）";
        return "来源为平台公开目录，缺少经核实的楼宇入口与可走路段；" + coordinateClue + "，暂不可算路。";
    }

    private static List<String> mergeAliases(List<String> first, List<String> second, String name) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        addAlias(merged, first, name);
        addAlias(merged, second, name);
        return List.copyOf(merged);
    }

    private static void addAlias(Set<String> target, List<String> aliases, String name) {
        if (aliases == null) {
            return;
        }
        for (String alias : aliases) {
            if (alias == null) {
                continue;
            }
            String trimmed = alias.trim();
            if (!trimmed.isEmpty() && !trimmed.equals(name)) {
                target.add(trimmed);
            }
        }
    }

    private static List<String> mergeSourceRefs(List<String> first, List<String> second) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (first != null) {
            merged.addAll(first);
        }
        if (second != null) {
            merged.addAll(second);
        }
        merged.remove(null);
        return List.copyOf(merged);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String describeResource() {
        return applicationPlaces == null ? "(未配置)" : applicationPlaces.getDescription();
    }

    private Snapshot requireSnapshot() {
        Snapshot current = snapshot;
        if (current == null) {
            String reason = loadErrorMessage == null ? "未知原因" : loadErrorMessage;
            throw new PlaceDataLoadException("地点数据尚未成功加载：" + reason);
        }
        return current;
    }

    private static String describeFailure(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception.getClass().getSimpleName();
        }
        return exception instanceof PlaceDataLoadException ? message : "地点数据加载异常：" + message;
    }

    private record Snapshot(String dataVersion, List<PlaceCandidate> candidates,
                            Map<String, PlaceCandidate> byId) {
    }

    private record AppDataset(String dataVersion, Map<String, AppPlace> placesById) {
    }

    private record AppPlace(String id, PlaceKind kind, String name, String category, List<String> aliases,
                            String verificationStatus, String verifiedAt, GeoPosition displayPosition,
                            GeoPosition routingPosition, String unavailableReason, List<String> sourceRefs) {
    }
}
