package cn.swjtu.zhixing.journey;

import cn.swjtu.zhixing.transit.CoordinateConverter;
import cn.swjtu.zhixing.transit.TransitGeometry;
import cn.swjtu.zhixing.transit.TransitPoint;
import java.util.ArrayList;
import java.util.List;

final class EastGateWalkLink {
    static final String SOURCE = "AMAP_WALKING_EAST_GATE_2026_11_19";
    private static final String EXIT_ID = "osm_node_13469658219";
    // 高德步行轨迹，团队已确认通行。经纬度为 GCJ-02。
    private static final List<List<Double>> PATH = List.of(
            List.of(103.993568, 30.769141),
            List.of(103.993268, 30.769058),
            List.of(103.993264, 30.769058));

    private EastGateWalkLink() {}

    static TransitGeometry extend(JourneyPoint exit, TransitGeometry original) {
        if (!EXIT_ID.equals(exit.pointId()) || original == null
                || !"GCJ-02".equals(original.coordinateSystem()) || original.points().size() < 2
                || !near(original.points().getFirst(), PATH.getLast())) {
            return original;
        }
        var gcjExit = CoordinateConverter.toGcj02(
                new TransitPoint(exit.pointId(), exit.longitude(), exit.latitude(), exit.coordinateSystem()));
        var start = List.of(gcjExit.longitude(), gcjExit.latitude());
        if (!near(start, PATH.getFirst())) {
            return original;
        }
        var points = new ArrayList<List<Double>>(PATH.size() + original.points().size());
        points.add(start);
        points.addAll(PATH.subList(1, PATH.size()));
        points.addAll(original.points());
        return new TransitGeometry(original.coordinateSystem(), points);
    }

    private static boolean near(List<Double> point, List<Double> reference) {
        return point.size() == 2 && Math.abs(point.get(0) - reference.get(0)) <= 0.000012
                && Math.abs(point.get(1) - reference.get(1)) <= 0.000012;
    }
}
