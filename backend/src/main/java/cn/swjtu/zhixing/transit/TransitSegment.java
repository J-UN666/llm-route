package cn.swjtu.zhixing.transit;

import java.util.List;

/** 城市段中的步行、公交、地铁或其他分段。lines 只包含实际选中的线路，不混入同段备选。 */
public record TransitSegment(
        String mode,
        Integer durationSeconds,
        Integer walkingMeters,
        String boardingStation,
        String alightingStation,
        List<TransitLine> lines,
        TransitGeometry geometry,
        String source) {
    public TransitSegment {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
