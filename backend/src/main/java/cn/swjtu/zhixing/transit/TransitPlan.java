package cn.swjtu.zhixing.transit;

import java.math.BigDecimal;
import java.util.List;

/** 统一城市段候选方案。distanceMeters 是步行和乘车的合计里程。 */
public record TransitPlan(
        Integer durationSeconds,
        Integer walkingMeters,
        Integer transferCount,
        BigDecimal costYuan,
        List<TransitSegment> segments,
        String source,
        Integer distanceMeters) {
    public TransitPlan {
        segments = segments == null ? List.of() : List.copyOf(segments);
    }

    public TransitPlan(Integer durationSeconds, Integer walkingMeters, Integer transferCount,
                       BigDecimal costYuan, List<TransitSegment> segments, String source) {
        this(durationSeconds, walkingMeters, transferCount, costYuan, segments, source, null);
    }
}
