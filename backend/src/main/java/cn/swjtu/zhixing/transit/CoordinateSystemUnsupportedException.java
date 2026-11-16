package cn.swjtu.zhixing.transit;

/** 适配层不支持的输入坐标系。 */
public class CoordinateSystemUnsupportedException extends RuntimeException {
    public CoordinateSystemUnsupportedException(String coordinateSystem) {
        super(coordinateSystem == null || coordinateSystem.isBlank()
                ? "coordinateSystem 不能为空"
                : "不支持的坐标系: " + coordinateSystem);
    }
}
