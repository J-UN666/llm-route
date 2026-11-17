package cn.swjtu.zhixing.assistant;

/**
 * 模型调用层的可识别失败，由 {@link ToolCheckService} 补充 requestId 后转换为
 * {@link AssistantApiException}。放在客户端层是为了让 HTTP 细节与业务错误体分离。
 */
public class ToolCallingException extends RuntimeException {

    private final int status;
    private final String code;
    private final boolean retryable;

    public ToolCallingException(int status, String code, String message, boolean retryable) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryable = retryable;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }
}
