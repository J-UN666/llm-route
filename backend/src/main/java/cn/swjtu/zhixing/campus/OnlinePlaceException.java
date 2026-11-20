package cn.swjtu.zhixing.campus;

import org.springframework.http.HttpStatus;

public class OnlinePlaceException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final boolean retryable;

    OnlinePlaceException(HttpStatus status, String code, String message, boolean retryable) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryable = retryable;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }
}
