package cn.swjtu.zhixing.campus;

import java.util.List;

/** GET /api/campus/connectors 响应：{originId, dataVersion, connectors:[CampusConnector]}。 */
public record CampusConnectorsResponse(
        String originId,
        String dataVersion,
        List<CampusConnector> connectors) {

    public CampusConnectorsResponse {
        connectors = connectors == null ? List.of() : List.copyOf(connectors);
    }
}
