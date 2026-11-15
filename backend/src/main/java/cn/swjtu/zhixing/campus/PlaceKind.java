package cn.swjtu.zhixing.campus;

import java.util.Locale;

/**
 * 地点候选类别：校园地点（含校园起点候选）、校门、城市目的地。
 *
 * <p>对应地点接口的 {@code scope} 参数；解析不区分大小写。</p>
 */
public enum PlaceKind {

    /** 校园内部地点/起终点候选（官方地点、OSM MVP 起点候选）。 */
    CAMPUS,

    /** 校门实体；稳定 ID 保留 campus_gate_east / campus_gate_west 等既有规范化 ID。 */
    GATE,

    /** 城市目的地候选。 */
    CITY;

    /**
     * 解析客户端传入的 scope。
     *
     * @return scope 为空时返回 {@code null}，表示不限类别
     * @throws InvalidScopeException scope 不是 CAMPUS/GATE/CITY
     */
    public static PlaceKind fromScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return null;
        }
        String normalized = scope.trim().toUpperCase(Locale.ROOT);
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw new InvalidScopeException(scope.trim());
        }
    }
}
