package cn.swjtu.zhixing.campus;

import java.util.List;

/**
 * 校门物理点位：同一个校门按不同行驶/步行方向保留独立点位。
 *
 * <p>{@code direction} 取值 {@code INBOUND}（进校）、{@code OUTBOUND}（出校）或 {@code UNKNOWN}
 * （方向未核实，仅作候选记录）；接驳终点必须使用 {@code OUTBOUND} 点，并写入 {@code gateExitPoint}。</p>
 */
public record CampusConnectorGatePoint(
        String pointId,
        Double longitude,
        Double latitude,
        String coordinateSystem,
        String direction,
        String positionRole,
        List<String> evidence) {

    public CampusConnectorGatePoint {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
