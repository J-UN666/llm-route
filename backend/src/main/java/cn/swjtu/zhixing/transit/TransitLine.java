package cn.swjtu.zhixing.transit;

/** 一条公交、地铁或铁路线路及其乘坐区间。 */
public record TransitLine(
        String name,
        String lineId,
        String type,
        String direction,
        String boardingStation,
        String alightingStation,
        Integer durationSeconds,
        Integer stopCount,
        TransitGeometry geometry,
        String source) {
}
