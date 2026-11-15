package cn.swjtu.zhixing.campus;

import java.util.List;

/**
 * 接驳几何：WGS84 经纬度折线（[longitude, latitude] 顺序）。
 *
 * <p>几何可为空；有几何时必须声明坐标系。接驳终点不使用校门中心，而使用实际出校点
 * {@code gateExitPoint}，几何折线首尾分别对应入口接入点与出校点。</p>
 */
public record CampusConnectorGeometry(
        String coordinateSystem,
        List<List<Double>> points) {

    public CampusConnectorGeometry {
        points = points == null ? List.of() : List.copyOf(points);
    }
}
