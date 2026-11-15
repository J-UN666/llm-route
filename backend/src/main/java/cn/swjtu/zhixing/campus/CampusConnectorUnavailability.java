package cn.swjtu.zhixing.campus;

/**
 * 某个校园起点下未返回的接驳及明确原因（随 422 NO_APPROVED_CONNECTOR 返回，供调用方判断）。
 *
 * <p>{@code reasonCode} 取值示例：{@code CONNECTOR_NOT_VERIFIED}、{@code GATE_EXIT_POINT_UNVERIFIED}、
 * {@code GATE_LOCKED}、{@code GATE_CLOSED_AT_DEPARTURE_TIME}、{@code PROHIBITED_SEGMENT}、
 * {@code INCOMPLETE_CONNECTOR}。</p>
 */
public record CampusConnectorUnavailability(
        String connectorId,
        String gateId,
        String verificationStatus,
        String reasonCode,
        String reason) {
}
