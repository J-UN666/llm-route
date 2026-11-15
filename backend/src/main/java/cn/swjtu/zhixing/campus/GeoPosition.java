package cn.swjtu.zhixing.campus;

/**
 * 地点位置对象：稳定点位 ID、经纬度、坐标系与位置角色。
 *
 * <p>官方来源坐标保留 {@code UNDECLARED} 坐标系与来源标注角色；显示位置与算路位置分开表达，
 * 未核实入口/出校点时算路位置为空。经纬度字段允许为 {@code null}（缺失不填 0）。</p>
 */
public record GeoPosition(
        String pointId,
        Double longitude,
        Double latitude,
        String coordinateSystem,
        String positionRole) {
}
