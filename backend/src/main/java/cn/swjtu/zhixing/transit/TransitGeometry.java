package cn.swjtu.zhixing.transit;

import java.util.ArrayList;
import java.util.List;

/** 路径折线；点为 [经度, 纬度]，坐标系随来源保留。 */
public record TransitGeometry(String coordinateSystem, List<List<Double>> points) {
    public TransitGeometry {
        points = points == null ? List.of() : copyPoints(points);
    }

    private static List<List<Double>> copyPoints(List<List<Double>> source) {
        List<List<Double>> copy = new ArrayList<>(source.size());
        for (List<Double> point : source) {
            copy.add(point == null ? List.of() : List.copyOf(point));
        }
        return List.copyOf(copy);
    }
}
