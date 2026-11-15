package cn.swjtu.zhixing.campus;

import java.util.List;

/** 接驳接口 422 错误体：{code, message, originId, originKind, dataVersion, unavailable:[...]}。 */
public record ConnectorErrorResponse(
        String code,
        String message,
        String originId,
        String originKind,
        String dataVersion,
        List<CampusConnectorUnavailability> unavailable) {

    public ConnectorErrorResponse {
        unavailable = unavailable == null ? List.of() : List.copyOf(unavailable);
    }
}
