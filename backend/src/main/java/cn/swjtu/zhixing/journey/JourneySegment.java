package cn.swjtu.zhixing.journey;

/**
 * 行程分段。校园步行段 {@code mode=CAMPUS_WALK}，城市段 {@code mode} 取
 * {@code WALK}、{@code BUS}、{@code METRO}；高德返回的其它交通方式按实际值透传
 * （如 {@code RAILWAY}、{@code TAXI}），不静默丢弃或改写成不存在的模式。
 *
 * <p>缺失指标保留 {@code null}：乘车段没有距离证据时 {@code distanceMeters=null}；
 * 几何缺失时 {@code geometry=null}、{@code coordinateSystem=null}。</p>
 */
public record JourneySegment(
        int sequence,
        String mode,
        String description,
        JourneyPoint fromPoint,
        JourneyPoint toPoint,
        String lineId,
        String lineName,
        String direction,
        String boardingStation,
        String alightingStation,
        Double distanceMeters,
        Integer durationSeconds,
        JourneyGeometry geometry,
        String coordinateSystem) {
}
