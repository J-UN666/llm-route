package cn.swjtu.zhixing.campus;

import java.util.List;

/**
 * 接驳点：稳定点位 ID、经纬度、坐标系、位置角色与核对证据。
 *
 * <p>接驳几何的起点/终点必须与 {@code pointId} 对应的 OSM 节点一致；坐标允许缺失（不填 0）。
 * 官方来源的坐标不得混入 WGS84 字段，来源证据放 {@code evidence}。</p>
 */
public record CampusConnectorPoint(
        String pointId,
        Double longitude,
        Double latitude,
        String coordinateSystem,
        String positionRole,
        List<String> evidence) {

    public CampusConnectorPoint {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
