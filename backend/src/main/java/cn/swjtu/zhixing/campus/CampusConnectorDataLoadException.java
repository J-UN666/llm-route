package cn.swjtu.zhixing.campus;

/** 接驳应用数据加载/校验失败（HTTP 500 / CONNECTOR_DATA_LOAD_FAILED）。 */
public class CampusConnectorDataLoadException extends RuntimeException {

    public CampusConnectorDataLoadException(String message) {
        super(message);
    }
}
