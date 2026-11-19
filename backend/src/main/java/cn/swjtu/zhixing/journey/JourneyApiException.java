package cn.swjtu.zhixing.journey;

import org.springframework.http.HttpStatus;

/**
 * 普通行程接口的业务异常；由 {@link JourneyApiExceptionHandler} 映射为统一错误体。
 *
 * <p>HTTP 映射：400 参数错误、404 未知地点、422 地点/接驳/坐标/时间能力不可用、
 * 502 上游失败、504 上游超时、500 校园数据加载失败或未分类内部错误。</p>
 */
public class JourneyApiException extends RuntimeException {

    private final String requestId;
    private final String code;
    private final HttpStatus status;
    private final boolean retryable;
    private final String causeCode;

    public JourneyApiException(HttpStatus status, String code, String message,
                               boolean retryable, String requestId) {
        this(status, code, message, retryable, requestId, code);
    }

    JourneyApiException(HttpStatus status, String code, String message,
                        boolean retryable, String requestId, String causeCode) {
        super(message);
        this.causeCode = causeCode;
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

    public String causeCode() {
        return causeCode;
    }

    public HttpStatus status() {
        return status;
    }

    public boolean retryable() {
        return retryable;
    }
}
