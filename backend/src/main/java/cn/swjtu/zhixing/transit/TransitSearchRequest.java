package cn.swjtu.zhixing.transit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;

/** 高德城市段查询入参；departureTime 必须带偏移量。 */
public record TransitSearchRequest(
        @NotNull(message = "originPoint 不能为空") @Valid TransitPoint originPoint,
        @NotNull(message = "destinationPoint 不能为空") @Valid TransitPoint destinationPoint,
        @NotNull(message = "departureTime 不能为空") OffsetDateTime departureTime,
        @NotNull(message = "preference 不能为空") TransitPreference preference) {
}
