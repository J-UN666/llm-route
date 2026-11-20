package cn.swjtu.zhixing.campus;

import cn.swjtu.zhixing.serviceconfig.PersonalServices.Credentials;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 地点搜索与按稳定 ID 查询。
 *
 * <p>匹配规则（校园语境）：先匹配正式名与已核实别名，再按前缀/包含做模糊匹配；
 * 不做编辑距离，避免“东门”误配“西门”之类跨实体猜测。同名实体各自保留独立 ID，
 * 结果按匹配强度排序后全部返回，由调用方消歧。关键词为空时返回所选 scope 的全部候选。</p>
 */
@Service
public class PlaceService {

    /** 关键词为空时的全量顺序：类别 → 名称 → 稳定 ID。 */
    private static final Comparator<PlaceCandidate> LIST_ORDER = Comparator
            .comparingInt((PlaceCandidate candidate) -> candidate.kind().ordinal())
            .thenComparing(PlaceCandidate::name)
            .thenComparing(PlaceCandidate::id);

    /** 命中排序：匹配强度降序 → 名称长度升序 → 类别 → 稳定 ID。 */
    private static final Comparator<Hit> HIT_ORDER = Comparator
            .comparingInt(Hit::score).reversed()
            .thenComparingInt(hit -> hit.candidate().name().length())
            .thenComparingInt(hit -> hit.candidate().kind().ordinal())
            .thenComparing(hit -> hit.candidate().id());

    private final CampusDataLoader campusDataLoader;
    private final Optional<AmapCityPlaces> onlinePlaces;

    PlaceService(CampusDataLoader campusDataLoader, Optional<AmapCityPlaces> onlinePlaces) {
        this.campusDataLoader = campusDataLoader;
        this.onlinePlaces = onlinePlaces;
    }

    /**
     * 按关键词与 scope 搜索地点。
     *
     * @param scope 空或 null 表示不限类别；非法值抛 {@link InvalidScopeException}（HTTP 400）
     */
    public List<PlaceCandidate> search(String keyword, String scope) {
        return search(keyword, PlaceKind.fromScope(scope));
    }

    public List<PlaceCandidate> search(String keyword, PlaceKind scope) {
        return search(keyword, scope, null);
    }

    List<PlaceCandidate> search(String keyword, PlaceKind scope, Credentials credentials) {
        List<PlaceCandidate> local = searchLocal(keyword, scope);
        String normalized = normalize(keyword);
        if (scope != PlaceKind.CITY || normalized.length() < 2 || onlinePlaces.isEmpty()
                || local.stream().anyMatch(place -> place.routingReady() && score(place, normalized) >= 96)) {
            return local;
        }
        List<PlaceCandidate> online = onlinePlaces.get().search(keyword.strip(), credentials).stream()
                .filter(place -> normalize(place.name()).contains(normalized)
                        || normalize(place.address()).contains(normalized))
                .toList();
        return online.isEmpty() ? local : online;
    }

    private List<PlaceCandidate> searchLocal(String keyword, PlaceKind scope) {
        List<PlaceCandidate> all = campusDataLoader.candidates();
        String normalizedKeyword = normalize(keyword);
        if (normalizedKeyword.isEmpty()) {
            return all.stream()
                    .filter(candidate -> scope == null || candidate.kind() == scope)
                    .sorted(LIST_ORDER)
                    .toList();
        }
        List<Hit> hits = new ArrayList<>();
        for (PlaceCandidate candidate : all) {
            if (scope != null && candidate.kind() != scope) {
                continue;
            }
            int score = score(candidate, normalizedKeyword);
            if (score > 0) {
                hits.add(new Hit(candidate, score));
            }
        }
        hits.sort(HIT_ORDER);
        return hits.stream().map(Hit::candidate).toList();
    }

    /** Prefer exact names and exact aliases together, including same-name candidates. */
    public List<PlaceCandidate> searchForRouting(String keyword, PlaceKind scope, Credentials credentials) {
        List<PlaceCandidate> matches = search(keyword, scope, credentials);
        String normalized = normalize(keyword);
        List<PlaceCandidate> exact = matches.stream()
                .filter(candidate -> score(candidate, normalized) >= 96)
                .toList();
        if (exact.isEmpty()) {
            return matches;
        }
        List<PlaceCandidate> physical = exact.stream()
                .filter(candidate -> !isDirectoryReference(candidate)).toList();
        // When an exact verified physical alias is available, directory-only organizations
        // remain searchable through the place API but cannot represent a departure point.
        return physical.stream().anyMatch(PlaceCandidate::routingReady) ? physical : exact;
    }

    public List<PlaceCandidate> search(String keyword) {
        return search(keyword, (PlaceKind) null);
    }

    /** 按稳定 ID 查询；不存在时抛 {@link PlaceNotFoundException}（HTTP 404 / PLACE_NOT_FOUND）。 */
    public PlaceCandidate getById(String id) {
        return getById(id, null);
    }

    public PlaceCandidate getById(String id, Credentials credentials) {
        String normalizedId = id == null ? "" : id.trim();
        if (AmapCityPlaces.isOnlineId(normalizedId)) {
            return onlinePlaces.orElseThrow(() -> new PlaceNotFoundException(normalizedId))
                    .getById(normalizedId, credentials);
        }
        return campusDataLoader.findById(normalizedId)
                .orElseThrow(() -> new PlaceNotFoundException(normalizedId));
    }

    /** 应用地点数据版本，供 W1-05/W1-06 与前端联调核对。 */
    public String dataVersion() {
        return campusDataLoader.dataVersion();
    }

    // ------------------------------------------------------------------

    private static boolean isDirectoryReference(PlaceCandidate candidate) {
        return !candidate.routingReady() && candidate.id().startsWith("swjtu_org_");
    }

    private static int score(PlaceCandidate candidate, String normalizedKeyword) {
        String name = normalize(candidate.name());
        if (name.equals(normalizedKeyword)) {
            return 100;
        }
        for (String alias : candidate.aliases()) {
            if (normalize(alias).equals(normalizedKeyword)) {
                return 96;
            }
        }
        // 单字关键词只做精确匹配，避免“门”“斋”之类单字产生大量噪声候选。
        if (normalizedKeyword.length() < 2) {
            return 0;
        }
        if (name.startsWith(normalizedKeyword)) {
            return 85;
        }
        for (String alias : candidate.aliases()) {
            if (normalize(alias).startsWith(normalizedKeyword)) {
                return 84;
            }
        }
        if (name.contains(normalizedKeyword)) {
            return 80;
        }
        for (String alias : candidate.aliases()) {
            if (normalize(alias).contains(normalizedKeyword)) {
                return 78;
            }
        }
        // 反向包含：用户输入更长（如“犀浦校区图书馆怎么走”），名称仍可命中。
        if (name.length() >= 2 && normalizedKeyword.contains(name)) {
            return 72;
        }
        for (String alias : candidate.aliases()) {
            String normalizedAlias = normalize(alias);
            if (normalizedAlias.length() >= 2 && normalizedKeyword.contains(normalizedAlias)) {
                return 70;
            }
        }
        return 0;
    }

    /** NFKC 归一化（全角/半角一致）后去除空白并转小写，中文不受影响。 */
    private static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        StringBuilder builder = new StringBuilder(normalized.length());
        for (int index = 0; index < normalized.length(); index++) {
            char ch = normalized.charAt(index);
            if (Character.isWhitespace(ch) || Character.isSpaceChar(ch)) {
                continue;
            }
            builder.append(ch);
        }
        return builder.toString().toLowerCase(Locale.ROOT);
    }

    private record Hit(PlaceCandidate candidate, int score) {
    }
}
