package cn.swjtu.zhixing.campus;

/** 按稳定 ID 查询地点失败，对应 HTTP 404 / PLACE_NOT_FOUND。 */
public class PlaceNotFoundException extends RuntimeException {

    private final String placeId;

    public PlaceNotFoundException(String placeId) {
        super("未找到地点: " + placeId);
        this.placeId = placeId;
    }

    public String placeId() {
        return placeId;
    }
}
