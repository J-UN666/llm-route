package cn.swjtu.zhixing.campus;

import java.util.List;

/**
 * 该校园起点在当前时刻没有审核可用的校门接驳（HTTP 422 / NO_APPROVED_CONNECTOR）。
 *
 * <p>错误体同时给出每个待核实/不通过/时段不可用连接的原因，避免调用方只能得到空列表。</p>
 */
public class NoApprovedConnectorException extends RuntimeException {

    private final String originId;
    private final String originKind;
    private final String dataVersion;
    private final List<CampusConnectorUnavailability> unavailable;

    public NoApprovedConnectorException(String message, String originId, String originKind,
                                        String dataVersion, List<CampusConnectorUnavailability> unavailable) {
        super(message);
        this.originId = originId;
        this.originKind = originKind;
        this.dataVersion = dataVersion;
        this.unavailable = unavailable == null ? List.of() : List.copyOf(unavailable);
    }

    public String originId() {
        return originId;
    }

    public String originKind() {
        return originKind;
    }

    public String dataVersion() {
        return dataVersion;
    }

    public List<CampusConnectorUnavailability> unavailable() {
        return unavailable;
    }
}
