package cn.swjtu.zhixing.campus;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * 校园入口到实际出校点的固定步行接驳服务（W1-05 / 供 W1-08 消费）。
 *
 * <p>数据来自应用层独立 JSON（默认 {@code classpath:data/campus-connectors.json}），启动时只读加载并校验
 * 分段加总、几何端点、是否包含禁行分段等不变量。服务只返回在当前 {@code departureTime} 下
 * 经确认或由项目启用试用且无开放限制的接驳；未启用、不通过或
 * 时段不可用的连接通过 {@link Resolution#unavailable()} 给出明确原因，供控制器返回 422。</p>
 *
 * <p>时间语义固定为 {@code Asia/Shanghai}；{@code departureTime} 必须是带时区的 ISO-8601 时间。</p>
 */
@Service
public class CampusConnectorService {

    private static final Logger log = LoggerFactory.getLogger(CampusConnectorService.class);

    /** 只有该状态的接驳才可能被返回。 */
    static final String VERIFICATION_APPROVED = "APPLICATION_VERIFIED";
    public static final String PROJECT_ASSUMED_OPEN = "PROJECT_ASSUMED_OPEN";
    /** 校园地点类型（{@link PlaceKind#CAMPUS}）。 */
    private static final String KIND_CAMPUS = "CAMPUS";
    static final ZoneId CAMPUS_ZONE = ZoneId.of("Asia/Shanghai");

    private final Resource applicationConnectors;
    private final ObjectMapper objectMapper;
    private final PlaceService placeService;

    private volatile Snapshot snapshot;
    private volatile String loadErrorMessage;

    public CampusConnectorService(
            @Value("${campus.application-connectors:classpath:data/campus-connectors.json}")
            Resource applicationConnectors,
            ObjectMapper objectMapper,
            PlaceService placeService) {
        this.applicationConnectors = applicationConnectors;
        this.objectMapper = objectMapper;
        this.placeService = placeService;
    }

    @PostConstruct
    public void load() {
        try {
            Snapshot loaded = parse();
            this.snapshot = loaded;
            this.loadErrorMessage = null;
            log.info("校园接驳应用数据加载完成：dataVersion={}，connectors={}", loaded.dataVersion(), loaded.connectors().size());
        } catch (RuntimeException ex) {
            this.snapshot = null;
            this.loadErrorMessage = "接驳应用数据加载失败：" + ex.getMessage();
            log.warn(this.loadErrorMessage, ex);
        }
    }

    /**
     * 返回该校园起点在当前出发时刻下审核可用的校门接驳（按估算秒数升序，其次按 ID）。
     *
     * @throws PlaceNotFoundException      未知地点
     * @throws InvalidOriginIdException    originId 为空
     * @throws InvalidDepartureTimeException departureTime 为空
     * @throws CampusConnectorDataLoadException 接驳数据加载/校验失败
     */
    public List<CampusConnector> getAvailableConnectors(String originId, OffsetDateTime departureTime) {
        return resolve(originId, departureTime).available();
    }

    /**
     * 解析一次查询：返回可返回的连接与所有未返回连接的原因，而不只是过滤后的列表。
     *
     * <p>供控制器区分「未知地点 404」「非校园起点/无审核连接 422」两种情况；W1-08 也可借此
     * 在组合行程时解释某个校门为何不可用。</p>
     */
    public Resolution resolve(String originId, OffsetDateTime departureTime) {
        return resolve(originId, departureTime, false);
    }

    /** 多方式行程检查预计走到门口时能否出校。 */
    public Resolution resolveAtExit(String originId, OffsetDateTime departureTime) {
        return resolve(originId, departureTime, true);
    }

    private Resolution resolve(String originId, OffsetDateTime departureTime, boolean checkAtExit) {
        if (originId == null || originId.isBlank()) {
            throw new InvalidOriginIdException("缺少 originId 查询参数，示例：originId=osm_way_1098786977");
        }
        if (departureTime == null) {
            throw new InvalidDepartureTimeException("缺少 departureTime 查询参数，示例：2026-11-15T09:00:00%2B08:00");
        }
        Snapshot current = requireSnapshot();
        String normalizedOriginId = originId.trim();
        PlaceCandidate origin = placeService.getById(normalizedOriginId);
        String originKind = origin.kind() == null ? "UNKNOWN" : origin.kind().name();

        ZonedDateTime departure = departureTime.atZoneSameInstant(CAMPUS_ZONE);
        if (!KIND_CAMPUS.equals(originKind)) {
            return new Resolution(normalizedOriginId, originKind, current.dataVersion(), List.of(), List.of());
        }

        List<CampusConnector> available = new ArrayList<>();
        List<CampusConnectorUnavailability> unavailable = new ArrayList<>();
        for (CampusConnector connector : current.byOrigin().getOrDefault(normalizedOriginId, List.of())) {
            Optional<CampusConnectorUnavailability> reason = evaluateAvailability(connector, departure, checkAtExit);
            if (reason.isPresent()) {
                unavailable.add(reason.get());
            } else {
                available.add(connector);
            }
        }
        available.sort(Comparator.comparing(CampusConnector::durationSeconds, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(CampusConnector::id));
        unavailable.sort(Comparator.comparing(CampusConnectorUnavailability::connectorId));
        return new Resolution(normalizedOriginId, originKind, current.dataVersion(), List.copyOf(available), List.copyOf(unavailable));
    }

    /** 当前接驳应用数据的版本号（未加载成功时抛 {@link CampusConnectorDataLoadException}）。 */
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
    // 可用性判断
    // ------------------------------------------------------------------

    private Optional<CampusConnectorUnavailability> evaluateAvailability(CampusConnector connector, ZonedDateTime departure,
                                                                           boolean checkAtExit) {
        String status = connector.verificationStatus();
        if (!usableStatus(status)) {
            String code = connector.unavailableReasonCode() == null || connector.unavailableReasonCode().isBlank()
                    ? "CONNECTOR_NOT_VERIFIED"
                    : connector.unavailableReasonCode();
            String reason = connector.unavailableReason() == null || connector.unavailableReason().isBlank()
                    ? "接驳未通过审核（verificationStatus=" + status + "），不能用于全程规划。"
                    : connector.unavailableReason();
            return Optional.of(unavailable(connector, code, reason));
        }
        if (connector.gateExitPoint() == null || connector.segments().isEmpty()
                || connector.walkMeters() == null || connector.durationSeconds() == null) {
            return Optional.of(unavailable(connector, "INCOMPLETE_CONNECTOR",
                    "已审核接驳缺少出校点、步行分段或总距离/总秒数，不能用于全程规划。"));
        }
        // 校门通行规则明确禁止行人通行时，即使其余字段完整也必须排除（W1-05 复审 P1）。
        if (connector.accessRules() != null
                && "PROHIBITED".equals(connector.accessRules().pedestrianAccess())) {
            return Optional.of(unavailable(connector, "PEDESTRIAN_ACCESS_PROHIBITED",
                    "校门通行规则明确禁止行人通行（accessRules.pedestrianAccess=PROHIBITED），该接驳不可用。"));
        }
        for (CampusConnectorSegment segment : connector.segments()) {
            if ("PROHIBITED".equals(segment.passabilityStatus())) {
                return Optional.of(unavailable(connector, "PROHIBITED_SEGMENT",
                        "接驳包含禁行分段 " + segment.segmentId() + "，不能用于全程规划。"));
            }
        }
        ZonedDateTime gateTime = checkAtExit ? departure.plusSeconds(connector.durationSeconds()) : departure;
        Optional<CampusConnectorTimeRestriction> closed = findClosedRestriction(connector.accessRules(), gateTime);
        if (closed.isPresent()) {
            CampusConnectorTimeRestriction restriction = closed.get();
            String detail = restriction.reason() == null || restriction.reason().isBlank()
                    ? "开放限制时段 " + restriction.from() + "–" + restriction.to()
                    : restriction.reason();
            String code = checkAtExit ? "GATE_CLOSED_AT_EXIT_TIME" : "GATE_CLOSED_AT_DEPARTURE_TIME";
            String timeLabel = checkAtExit ? "预计出校时刻 " : "出发时刻 ";
            return Optional.of(unavailable(connector, code,
                    timeLabel + gateTime.toLocalTime() + " 位于不可用时段 " + restriction.from() + "–" + restriction.to()
                            + "（Asia/Shanghai）：" + detail));
        }
        return Optional.empty();
    }

    private static CampusConnectorUnavailability unavailable(CampusConnector connector, String reasonCode, String reason) {
        return new CampusConnectorUnavailability(connector.id(), connector.gateId(), connector.verificationStatus(), reasonCode, reason);
    }

    private Optional<CampusConnectorTimeRestriction> findClosedRestriction(CampusConnectorAccessRules rules, ZonedDateTime departure) {
        if (rules == null || rules.timeRestrictions().isEmpty()) {
            return Optional.empty();
        }
        LocalTime time = departure.toLocalTime();
        DayOfWeek day = departure.getDayOfWeek();
        for (CampusConnectorTimeRestriction restriction : rules.timeRestrictions()) {
            if (isClosed(restriction, day, time)) {
                return Optional.of(restriction);
            }
        }
        return Optional.empty();
    }

    /**
     * 判断某一时刻是否落在限制时段内。支持跨夜：例如 MONDAY 23:00–06:00 覆盖周一 23:00–24:00
     * 与周二 00:00–06:00；{@code days} 为空表示每天生效。
     */
    static boolean isClosed(CampusConnectorTimeRestriction restriction, DayOfWeek day, LocalTime time) {
        LocalTime from = parseRestrictionTime(restriction.from());
        LocalTime to = parseRestrictionTime(restriction.to());
        boolean matchesDay = restriction.days().isEmpty() || restriction.days().contains(day);
        if (from.equals(to)) {
            // 起止相同视为全天不可用。
            return matchesDay;
        }
        if (from.isBefore(to)) {
            return matchesDay && !time.isBefore(from) && time.isBefore(to);
        }
        // 跨夜：开始日 23:00–24:00 或次日 00:00–to。
        boolean startDayHit = matchesDay && !time.isBefore(from);
        boolean previousDayInWindow = restriction.days().isEmpty() || restriction.days().contains(day.minus(1));
        boolean continuation = previousDayInWindow && time.isBefore(to);
        return startDayHit || continuation;
    }

    private static LocalTime parseRestrictionTime(String value) {
        if (value == null || value.isBlank()) {
            throw new CampusConnectorDataLoadException("开放限制时段缺少 from/to 时间（HH:mm）");
        }
        try {
            return LocalTime.parse(value);
        } catch (DateTimeParseException ex) {
            throw new CampusConnectorDataLoadException("开放限制时段不是合法的 HH:mm 时间：" + value);
        }
    }

    // ------------------------------------------------------------------
    // 加载与校验
    // ------------------------------------------------------------------

    private Snapshot parse() {
        JsonNode root;
        try (InputStream in = applicationConnectors.getInputStream()) {
            root = objectMapper.readTree(in);
        } catch (IOException ex) {
            throw new CampusConnectorDataLoadException("读取接驳应用数据失败（" + describeResource() + "）：" + ex.getMessage());
        }
        if (root == null || !root.isObject()) {
            throw new CampusConnectorDataLoadException("接驳应用数据顶层必须是 JSON 对象（" + describeResource() + "）");
        }
        String dataVersion = requiredText(root, "dataVersion", "顶层");
        JsonNode connectorsNode = root.get("connectors");
        if (connectorsNode == null || !connectorsNode.isArray() || connectorsNode.isEmpty()) {
            throw new CampusConnectorDataLoadException("顶层 connectors 必须是非空数组（" + describeResource() + "）");
        }

        List<CampusConnector> connectors = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        List<String> originIds = new ArrayList<>();
        for (int index = 0; index < connectorsNode.size(); index++) {
            CampusConnector connector;
            try {
                connector = objectMapper.treeToValue(connectorsNode.get(index), CampusConnector.class);
            } catch (JsonProcessingException ex) {
                throw new CampusConnectorDataLoadException("第 " + (index + 1) + " 条接驳字段无法映射：" + ex.getOriginalMessage());
            }
            validateConnector(connector, index + 1);
            if (!ids.add(connector.id())) {
                throw new CampusConnectorDataLoadException("接驳 ID 重复：" + connector.id());
            }
            connectors.add(connector);
            originIds.add(connector.originId());
        }

        Map<String, List<CampusConnector>> byOrigin = connectors.stream()
                .collect(Collectors.groupingBy(CampusConnector::originId, LinkedHashMap::new, Collectors.toList()));
        Map<String, List<CampusConnector>> immutableByOrigin = new HashMap<>();
        byOrigin.forEach((originId, list) -> immutableByOrigin.put(originId, List.copyOf(list)));
        return new Snapshot(dataVersion, List.copyOf(connectors), Map.copyOf(immutableByOrigin), List.copyOf(originIds));
    }

    private void validateConnector(CampusConnector connector, int index) {
        String label = "第 " + index + " 条接驳（" + (connector.id() == null ? "无 id" : connector.id()) + "）";
        if (isBlank(connector.id())) {
            throw new CampusConnectorDataLoadException(label + "缺少 id");
        }
        label = "接驳 " + connector.id();
        if (isBlank(connector.originId())) {
            throw new CampusConnectorDataLoadException(label + "缺少 originId");
        }
        validatePoint(connector.originPoint(), label + " 的 originPoint");
        if (isBlank(connector.gateId())) {
            throw new CampusConnectorDataLoadException(label + "缺少 gateId");
        }
        for (CampusConnectorGatePoint gatePoint : connector.gatePoints()) {
            validateGatePoint(gatePoint, label + " 的 gatePoints[" + gatePoint.pointId() + "]");
            if (isBlank(gatePoint.direction())) {
                throw new CampusConnectorDataLoadException(label + " 的校门点位 " + gatePoint.pointId() + " 缺少 direction");
            }
        }

        if (usableStatus(connector.verificationStatus())) {
            validateApprovedConnector(connector, label);
        }

        CampusConnectorAccessRules rules = connector.accessRules();
        if (rules != null) {
            for (CampusConnectorTimeRestriction restriction : rules.timeRestrictions()) {
                parseRestrictionTime(restriction.from());
                parseRestrictionTime(restriction.to());
            }
        }
    }

    private static boolean usableStatus(String status) {
        return VERIFICATION_APPROVED.equals(status) || PROJECT_ASSUMED_OPEN.equals(status);
    }

    private void validateApprovedConnector(CampusConnector connector, String label) {
        if (connector.gateExitPoint() == null) {
            throw new CampusConnectorDataLoadException(label + " 已审核但缺少 gateExitPoint");
        }
        validatePoint(connector.gateExitPoint(), label + " 的 gateExitPoint");
        boolean exitPointIsOutbound = connector.gatePoints().stream()
                .anyMatch(gatePoint -> "OUTBOUND".equals(gatePoint.direction())
                        && gatePoint.pointId().equals(connector.gateExitPoint().pointId()));
        if (!exitPointIsOutbound) {
            throw new CampusConnectorDataLoadException(label + " 的 gateExitPoint(" + connector.gateExitPoint().pointId()
                    + ") 不是 gatePoints 中的 OUTBOUND 物理点位");
        }
        if (connector.segments().isEmpty()) {
            throw new CampusConnectorDataLoadException(label + " 已审核但没有步行分段");
        }
        if (connector.walkMeters() == null || connector.durationSeconds() == null || isBlank(connector.durationBasis())) {
            throw new CampusConnectorDataLoadException(label + " 缺少 walkMeters/durationSeconds/durationBasis");
        }
        double sumMeters = 0;
        long sumSeconds = 0;
        for (int index = 0; index < connector.segments().size(); index++) {
            CampusConnectorSegment segment = connector.segments().get(index);
            if (segment.order() != index + 1) {
                throw new CampusConnectorDataLoadException(label + " 的分段 order 必须从 1 连续递增，实际第 "
                        + (index + 1) + " 段为 " + segment.order());
            }
            if (segment.lengthMeters() <= 0 || segment.durationSeconds() <= 0) {
                throw new CampusConnectorDataLoadException(label + " 的 " + segment.segmentId() + " 米数/秒数必须为正");
            }
            if (isBlank(segment.passabilityStatus())) {
                throw new CampusConnectorDataLoadException(label + " 的 " + segment.segmentId() + " 缺少通行状态");
            }
            CampusConnectorGeometry geometry = segment.geometry();
            if (geometry != null) {
                if (!"WGS84".equals(geometry.coordinateSystem())) {
                    throw new CampusConnectorDataLoadException(label + " 的 " + segment.segmentId() + " 几何坐标系必须为 WGS84");
                }
                if (geometry.points().size() < 2) {
                    throw new CampusConnectorDataLoadException(label + " 的 " + segment.segmentId() + " 几何折线至少需要 2 个点");
                }
            }
            if (index > 0) {
                CampusConnectorSegment previous = connector.segments().get(index - 1);
                if (!previous.toPointId().equals(segment.fromPointId())) {
                    throw new CampusConnectorDataLoadException(label + " 的分段端点不连续：" + previous.segmentId()
                            + " 终点 " + previous.toPointId() + " 与 " + segment.segmentId() + " 起点 " + segment.fromPointId() + " 不一致");
                }
            }
            sumMeters += segment.lengthMeters();
            sumSeconds += segment.durationSeconds();
        }
        if (Math.abs(sumMeters - connector.walkMeters()) > 0.1) {
            throw new CampusConnectorDataLoadException(label + " 的 walkMeters(" + connector.walkMeters()
                    + ") 与分段加总(" + sumMeters + ") 不一致");
        }
        if (sumSeconds != connector.durationSeconds()) {
            throw new CampusConnectorDataLoadException(label + " 的 durationSeconds(" + connector.durationSeconds()
                    + ") 与分段加总(" + sumSeconds + ") 不一致");
        }
        CampusConnectorSegment first = connector.segments().get(0);
        CampusConnectorSegment last = connector.segments().get(connector.segments().size() - 1);
        if (!first.fromPointId().equals(connector.originPoint().pointId())) {
            throw new CampusConnectorDataLoadException(label + " 的首段起点 " + first.fromPointId()
                    + " 与 originPoint.pointId(" + connector.originPoint().pointId() + ") 不一致");
        }
        if (!last.toPointId().equals(connector.gateExitPoint().pointId())) {
            throw new CampusConnectorDataLoadException(label + " 的末段终点 " + last.toPointId()
                    + " 与 gateExitPoint.pointId(" + connector.gateExitPoint().pointId() + ") 不一致");
        }
        validateGeometryEndpoints(connector, first, last, label);
    }

    private void validateGeometryEndpoints(CampusConnector connector, CampusConnectorSegment first,
                                           CampusConnectorSegment last, String label) {
        if (first.geometry() != null) {
            assertCoordinateEquals(first.geometry().points().get(0), connector.originPoint().longitude(),
                    connector.originPoint().latitude(), label + " 首段几何起点与 originPoint 坐标");
        }
        if (last.geometry() != null) {
            assertCoordinateEquals(last.geometry().points().get(last.geometry().points().size() - 1),
                    connector.gateExitPoint().longitude(), connector.gateExitPoint().latitude(),
                    label + " 末段几何终点与 gateExitPoint 坐标");
        }
    }

    private static void assertCoordinateEquals(List<Double> point, Double expectedLon, Double expectedLat, String label) {
        if (point.size() < 2 || expectedLon == null || expectedLat == null) {
            throw new CampusConnectorDataLoadException(label + " 无法核对（缺少坐标）");
        }
        if (Math.abs(point.get(0) - expectedLon) > 1e-6 || Math.abs(point.get(1) - expectedLat) > 1e-6) {
            throw new CampusConnectorDataLoadException(label + " 不一致：几何(" + point.get(0) + "," + point.get(1)
                    + ") 期望(" + expectedLon + "," + expectedLat + ")");
        }
    }

    private static void validateGatePoint(CampusConnectorGatePoint point, String label) {
        if (point == null) {
            throw new CampusConnectorDataLoadException(label + " 缺失");
        }
        validatePointFields(point.pointId(), point.longitude(), point.latitude(), point.coordinateSystem(), label);
    }

    private static void validatePoint(CampusConnectorPoint point, String label) {
        if (point == null) {
            throw new CampusConnectorDataLoadException(label + " 缺失");
        }
        validatePointFields(point.pointId(), point.longitude(), point.latitude(), point.coordinateSystem(), label);
    }

    private static void validatePointFields(String pointId, Double longitude, Double latitude,
                                            String coordinateSystem, String label) {
        if (isBlank(pointId)) {
            throw new CampusConnectorDataLoadException(label + " 缺少 pointId");
        }
        if (longitude == null || latitude == null) {
            throw new CampusConnectorDataLoadException(label + "(" + pointId + ") 缺少经纬度");
        }
        if (longitude < -180 || longitude > 180 || latitude < -90 || latitude > 90) {
            throw new CampusConnectorDataLoadException(label + "(" + pointId + ") 经纬度越界");
        }
        if (isBlank(coordinateSystem)) {
            throw new CampusConnectorDataLoadException(label + "(" + pointId + ") 缺少坐标系");
        }
    }

    private static String requiredText(JsonNode node, String field, String label) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new CampusConnectorDataLoadException(label + "缺少字段 " + field);
        }
        return value.asText();
    }

    private Snapshot requireSnapshot() {
        Snapshot current = snapshot;
        if (current == null) {
            String message = loadErrorMessage == null
                    ? "校园接驳应用数据尚未加载"
                    : loadErrorMessage;
            throw new CampusConnectorDataLoadException(message);
        }
        return current;
    }

    private String describeResource() {
        try {
            return applicationConnectors.getDescription();
        } catch (RuntimeException ex) {
            return String.valueOf(applicationConnectors);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 加载后的接驳数据快照：同一次启动内保持稳定，重启读取同版 JSON 得到相同 ID/版本。 */
    record Snapshot(String dataVersion, List<CampusConnector> connectors,
                    Map<String, List<CampusConnector>> byOrigin, List<String> originIds) {
    }

    /**
     * 一次查询的解析结果。
     *
     * @param originId   归一化后的起点 ID
     * @param originKind 起点类型（{@code CAMPUS}/{@code GATE}/{@code CITY}），非 CAMPUS 时 available 必为空
     * @param available  当前时刻可用的接驳
     * @param unavailable 未返回的接驳及原因（含待核实、不通过、禁行、时段不可用等）
     */
    public record Resolution(String originId, String originKind, String dataVersion,
                             List<CampusConnector> available, List<CampusConnectorUnavailability> unavailable) {

        public Resolution {
            available = available == null ? List.of() : List.copyOf(available);
            unavailable = unavailable == null ? List.of() : List.copyOf(unavailable);
        }
    }
}
