package cn.swjtu.zhixing.campus;

import java.util.List;

/**
 * 接驳步行分段：按顺序保存说明、米数、估算秒数、通行状态与 WGS84 几何。
 *
 * <p>{@code walkMeters}/{@code durationSeconds} 由全部分段加总核对；几何折线首尾与
 * {@code fromPointId}/{@code toPointId} 对应节点一致。{@code passabilityStatus} 取值
 * {@code OPEN}、{@code OPEN_UNVERIFIED}、{@code CONDITIONAL}、{@code PROHIBITED}
 * （禁行分段不得出现在已审核接驳中）。</p>
 */
public record CampusConnectorSegment(
        String segmentId,
        int order,
        String description,
        String fromPointId,
        String toPointId,
        double lengthMeters,
        int durationSeconds,
        String highway,
        String roadName,
        String passabilityStatus,
        String passabilityNote,
        CampusConnectorGeometry geometry,
        List<String> osmWayIds,
        List<String> sourceRefs) {

    public CampusConnectorSegment {
        osmWayIds = osmWayIds == null ? List.of() : List.copyOf(osmWayIds);
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
    }
}
