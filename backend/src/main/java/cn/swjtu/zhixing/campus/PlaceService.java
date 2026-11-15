package cn.swjtu.zhixing.campus;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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

    public PlaceService(CampusDataLoader campusDataLoader) {
        this.campusDataLoader = campusDataLoader;
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

    public List<PlaceCandidate> search(String keyword) {
        return search(keyword, (PlaceKind) null);
    }

    /** 按稳定 ID 查询；不存在时抛 {@link PlaceNotFoundException}（HTTP 404 / PLACE_NOT_FOUND）。 */
    public PlaceCandidate getById(String id) {
        String normalizedId = id == null ? "" : id.trim();
        return campusDataLoader.findById(normalizedId)
                .orElseThrow(() -> new PlaceNotFoundException(normalizedId));
    }

    /** 应用地点数据版本，供 W1-05/W1-06 与前端联调核对。 */
    public String dataVersion() {
        return campusDataLoader.dataVersion();
    }

    // ------------------------------------------------------------------

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
