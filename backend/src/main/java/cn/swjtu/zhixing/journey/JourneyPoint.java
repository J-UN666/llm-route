package cn.swjtu.zhixing.journey;

/**
 * 行程段端点或线路站点。
 *
 * <p>经度在前、纬度在后（与 W1-04/W1-06 一致）。坐标缺失时保留 {@code null}，不填 0。
 * 坐标系随点位来源保留：校园点位为 {@code WGS84}，高德分段几何为 {@code GCJ-02}。</p>
 */
public record JourneyPoint(
        String pointId,
        String name,
        Double longitude,
        Double latitude,
        String coordinateSystem,
        String positionRole) {
}
