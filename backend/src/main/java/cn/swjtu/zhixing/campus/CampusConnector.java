package cn.swjtu.zhixing.campus;

import java.util.List;

/**
 * 校园入口到实际出校点的固定步行接驳（W1-05 DTO）。
 *
 * <p>契约见 docs/contracts/campus-connector-api.md。约定：
 * <ul>
 *   <li>接驳终点是实际出校点 {@code gateExitPoint}，不是校门中心；同一校门的不同方向
 *       物理点位保存在 {@code gatePoints}；</li>
 *   <li>{@code walkMeters}/{@code durationSeconds} 必须等于 {@code segments} 的加总；
 *       几何端点与起点/出校点一致；</li>
 *   <li>只有 {@code verificationStatus=APPLICATION_VERIFIED} 且按时段规则可用的接驳才会
 *       被服务返回；未审核/不通过连接保留在应用 JSON 中并给出明确原因。</li>
 * </ul>
 */
public record CampusConnector(
        String id,
        String originId,
        String originName,
        CampusConnectorPoint originPoint,
        String gateId,
        String gateName,
        List<CampusConnectorGatePoint> gatePoints,
        CampusConnectorPoint gateExitPoint,
        List<CampusConnectorSegment> segments,
        Double walkMeters,
        Integer durationSeconds,
        String durationBasis,
        String verificationStatus,
        String verifiedAt,
        String verificationMethod,
        List<String> verificationBasis,
        List<String> missingEvidence,
        CampusConnectorAccessRules accessRules,
        List<String> sourceRefs,
        String unavailableReasonCode,
        String unavailableReason) {

    public CampusConnector {
        gatePoints = gatePoints == null ? List.of() : List.copyOf(gatePoints);
        segments = segments == null ? List.of() : List.copyOf(segments);
        verificationBasis = verificationBasis == null ? List.of() : List.copyOf(verificationBasis);
        missingEvidence = missingEvidence == null ? List.of() : List.copyOf(missingEvidence);
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
    }
}
