package cn.swjtu.zhixing.transit;

import java.time.OffsetDateTime;
import java.util.List;

/** 城市段查询结果。errorMessage 不包含 key、端点坐标或完整请求 URL。 */
public record TransitSearchResult(
        String provider,
        OffsetDateTime queriedAt,
        OffsetDateTime requestedDepartureTime,
        OffsetDateTime providerDepartureTime,
        int roundingWaitSeconds,
        TransitStatus status,
        List<TransitPlan> plans,
        TransitErrorCode errorCode,
        String errorMessage) {
    public TransitSearchResult {
        plans = plans == null ? List.of() : List.copyOf(plans);
    }

    public TransitSearchResult(String provider,
                               OffsetDateTime queriedAt,
                               OffsetDateTime requestedDepartureTime,
                               OffsetDateTime providerDepartureTime,
                               int roundingWaitSeconds,
                               TransitStatus status,
                               List<TransitPlan> plans) {
        this(provider, queriedAt, requestedDepartureTime, providerDepartureTime,
                roundingWaitSeconds, status, plans, null, null);
    }
}
