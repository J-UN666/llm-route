package cn.swjtu.zhixing.transit;

import java.util.Locale;

/** 与 web/chengdu-map.js 一致的 WGS84 → GCJ-02 转换。 */
public final class CoordinateConverter {
    private static final double PI = Math.PI;
    private static final double AXIS = 6378245.0;
    private static final double EE = 0.00669342162296594323;

    private CoordinateConverter() {
    }

    public static TransitPoint toGcj02(TransitPoint point) {
        if (point == null) {
            throw new IllegalArgumentException("点位不能为空");
        }
        String system = point.coordinateSystem() == null
                ? ""
                : point.coordinateSystem().trim().toUpperCase(Locale.ROOT);
        if ("GCJ-02".equals(system) || "GCJ02".equals(system)) {
            return new TransitPoint(point.pointId(), point.longitude(), point.latitude(), "GCJ-02");
        }
        if (!"WGS84".equals(system) && !"WGS-84".equals(system)) {
            throw new CoordinateSystemUnsupportedException(point.coordinateSystem());
        }
        if (outOfChina(point.longitude(), point.latitude())) {
            return new TransitPoint(point.pointId(), point.longitude(), point.latitude(), "GCJ-02");
        }
        double dLat = transformLat(point.longitude() - 105.0, point.latitude() - 35.0);
        double dLng = transformLng(point.longitude() - 105.0, point.latitude() - 35.0);
        double radLat = point.latitude() / 180.0 * PI;
        double magic = Math.sin(radLat);
        magic = 1 - EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((AXIS * (1 - EE)) / (magic * sqrtMagic) * PI);
        dLng = (dLng * 180.0) / (AXIS / sqrtMagic * Math.cos(radLat) * PI);
        return new TransitPoint(point.pointId(), point.longitude() + dLng, point.latitude() + dLat, "GCJ-02");
    }

    private static boolean outOfChina(double longitude, double latitude) {
        return !(longitude > 73.66 && longitude < 135.05 && latitude > 3.86 && latitude < 53.55);
    }

    private static double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y
                + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * PI) + 40.0 * Math.sin(y / 3.0 * PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * PI) + 320 * Math.sin(y * PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private static double transformLng(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x
                + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * PI) + 40.0 * Math.sin(x / 3.0 * PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * PI) + 300.0 * Math.sin(x / 30.0 * PI)) * 2.0 / 3.0;
        return ret;
    }
}
