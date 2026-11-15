package cn.swjtu.zhixing.campus;

/**
 * 地点数据加载失败（文件缺失、JSON 格式错误、字段/ID 校验失败等）。
 *
 * <p>不吞掉具体原因：错误消息要能直接指明缺失文件、出错字段或非法 id。</p>
 */
public class PlaceDataLoadException extends RuntimeException {

    public PlaceDataLoadException(String message) {
        super(message);
    }

    public PlaceDataLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
