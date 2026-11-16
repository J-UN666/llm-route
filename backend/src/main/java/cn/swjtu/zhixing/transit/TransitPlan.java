package cn.swjtu.zhixing.transit;

import java.math.BigDecimal;
import java.util.List;

/** 统一城市段候选方案。缺失票价保持 null。 */
public record TransitPlan(
        Integer durationSeconds,
        Integer walkingMeters,
        Integer transferCount,
        BigDecimal costYuan,
        List<TransitSegment> segments,
        String source) {
    public TransitPlan {
        segments = segments == null ? List.of() : List.copyOf(segments);
    }
}
