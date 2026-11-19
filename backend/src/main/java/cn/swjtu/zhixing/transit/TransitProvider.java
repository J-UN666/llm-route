package cn.swjtu.zhixing.transit;

import java.time.OffsetDateTime;

/** 城市公共交通查询提供方统一接口。 */
public interface TransitProvider {
    TransitSearchResult search(TransitSearchRequest request);

    default TransitSearchResult searchWithKey(TransitSearchRequest request, String key) {
        throw new UnsupportedOperationException("当前地图服务不支持个人配置");
    }

    /** 便于业务层直接以两个稳定点位调用；等价于构造 TransitSearchRequest。 */
    default TransitSearchResult search(TransitPoint originPoint,
                                       TransitPoint destinationPoint,
                                       OffsetDateTime departureTime,
                                       TransitPreference preference) {
        return search(new TransitSearchRequest(originPoint, destinationPoint, departureTime, preference));
    }
}
