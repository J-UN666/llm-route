package cn.swjtu.zhixing.journey;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 一条完整候选方案。城市地点出发时，校门字段为空，校园接驳耗时为零。
 *
 * <p>时间合计口径：{@code departureTime} 为请求的校园出发时刻；
 * {@code cityDepartureTime = departureTime + 接驳 durationSeconds}；
 * {@code providerDepartureTime} 为城市提供方取整后的出发时刻；
 * {@code arrivalTime = providerDepartureTime + 城市段 durationSeconds}；
 * {@code totalDurationSeconds = arrivalTime - departureTime}（= 接驳秒数 + 取整等待 + 城市段秒数）。</p>
 *
 * <p>城市段耗时与步行距离已经包含高德方案内的到站步行，组合时只累加一次；
 * 费用未知与步行距离未知均保留 {@code null}。</p>
 */
public record JourneyPlan(
        String planId,
        String originId,
        String destinationId,
        String gateId,
        JourneyPoint gateExitPoint,
        OffsetDateTime departureTime,
        OffsetDateTime cityDepartureTime,
        OffsetDateTime providerDepartureTime,
        Integer roundingWaitSeconds,
        OffsetDateTime arrivalTime,
        Integer totalDurationSeconds,
        BigDecimal totalWalkingMeters,
        Integer transferCount,
        BigDecimal costYuan,
        List<JourneySegment> segments,
        List<String> sources,
        OffsetDateTime queriedAt,
        String campusDataVersion,
        JourneyPoint destinationPoint,
        @JsonInclude(JsonInclude.Include.NON_NULL) JourneyPoint originPoint,
        @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal totalDistanceMeters) {

    public JourneyPlan {
        segments = segments == null ? List.of() : List.copyOf(segments);
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    public JourneyPlan(String planId, String originId, String destinationId, String gateId,
                       JourneyPoint gateExitPoint, OffsetDateTime departureTime, OffsetDateTime cityDepartureTime,
                       OffsetDateTime providerDepartureTime, Integer roundingWaitSeconds, OffsetDateTime arrivalTime,
                       Integer totalDurationSeconds, BigDecimal totalWalkingMeters, Integer transferCount,
                       BigDecimal costYuan, List<JourneySegment> segments, List<String> sources,
                       OffsetDateTime queriedAt, String campusDataVersion, JourneyPoint destinationPoint) {
        this(planId, originId, destinationId, gateId, gateExitPoint, departureTime, cityDepartureTime,
                providerDepartureTime, roundingWaitSeconds, arrivalTime, totalDurationSeconds, totalWalkingMeters,
                transferCount, costYuan, segments, sources, queriedAt, campusDataVersion, destinationPoint, null, null);
    }
}
