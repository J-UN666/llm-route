package cn.swjtu.zhixing.assistant;

import org.springframework.http.HttpStatus;

/** 模型工具调用自检接口的业务异常；由 {@link AssistantApiExceptionHandler} 映射为统一错误体。 */
public class AssistantApiException extends RuntimeException {

    private final String requestId;
    private final String code;
    private final HttpStatus status;
    private final boolean retryable;

    public AssistantApiException(HttpStatus status, String code, String message,
                                 boolean retryable, String requestId) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryable = retryable;
        this.requestId = requestId;
    }

    public String requestId() {
        return requestId;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public boolean retryable() {
        return retryable;
    }
}
