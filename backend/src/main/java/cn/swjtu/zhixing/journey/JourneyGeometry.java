package cn.swjtu.zhixing.journey;

import java.util.ArrayList;
import java.util.List;

/**
 * 行程段折线；点为 {@code [经度, 纬度]}。
 *
 * <p>坐标系在 {@code coordinateSystem} 显式标注，源自校园数据的段为 {@code WGS84}，
 * 源自高德的段为 {@code GCJ-02}，不混合、不静默转换。几何缺失时整段 {@code geometry=null}。</p>
 */
public record JourneyGeometry(
        String type,
        List<List<Double>> coordinates,
        String coordinateSystem) {

    public static final String LINE_STRING = "LineString";

    public JourneyGeometry {
        coordinates = copyPoints(coordinates);
    }

    private static List<List<Double>> copyPoints(List<List<Double>> source) {
        if (source == null) {
            return List.of();
        }
        List<List<Double>> copy = new ArrayList<>(source.size());
        for (List<Double> point : source) {
            copy.add(point == null ? List.of() : List.copyOf(point));
        }
        return List.copyOf(copy);
    }
}
