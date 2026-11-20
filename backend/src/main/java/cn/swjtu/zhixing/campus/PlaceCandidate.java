package cn.swjtu.zhixing.campus;

import java.util.List;

/**
 * 地点候选：小程序地点搜索、选择与联调使用的稳定返回结构。
 *
 * <p>字段契约见 docs/api.md。约定：
 * <ul>
 *   <li>id 是来源稳定 ID，同名实体各自保留独立 ID，不按名称合并；</li>
 *   <li>routingReady 为 true 表示已有可传给路线服务的位置；在线地点注明入口或标注点来源；</li>
 *   <li>routingPosition 为空时必须保留 unavailableReason，说明缺哪类入口/出校点或坐标证据；</li>
 *   <li>displayPosition 只作展示线索，楼宇中心/官方标注点不是入口。</li>
 * </ul>
 */
public record PlaceCandidate(
        String id,
        String name,
        PlaceKind kind,
        String category,
        List<String> aliases,
        boolean routingReady,
        String unavailableReason,
        String verificationStatus,
        GeoPosition displayPosition,
        GeoPosition routingPosition,
        String dataVersion,
        List<String> sourceRefs,
        String address) {

    public PlaceCandidate(String id, String name, PlaceKind kind, String category, List<String> aliases,
                          boolean routingReady, String unavailableReason, String verificationStatus,
                          GeoPosition displayPosition, GeoPosition routingPosition, String dataVersion,
                          List<String> sourceRefs) {
        this(id, name, kind, category, aliases, routingReady, unavailableReason, verificationStatus,
                displayPosition, routingPosition, dataVersion, sourceRefs, null);
    }

    public PlaceCandidate {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
    }
}
