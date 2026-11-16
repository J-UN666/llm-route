package cn.swjtu.zhixing.transit;

import java.time.OffsetDateTime;

/** 城市公共交通查询提供方统一接口。 */
public interface TransitProvider {
    TransitSearchResult search(TransitSearchRequest request);

    /** 便于业务层直接以两个稳定点位调用；等价于构造 TransitSearchRequest。 */
    default TransitSearchResult search(TransitPoint originPoint,
                                       TransitPoint destinationPoint,
                                       OffsetDateTime departureTime,
                                       TransitPreference preference) {
        return search(new TransitSearchRequest(originPoint, destinationPoint, departureTime, preference));
    }
}
