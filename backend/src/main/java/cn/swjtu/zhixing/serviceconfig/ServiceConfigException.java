package cn.swjtu.zhixing.serviceconfig;

import org.springframework.http.HttpStatus;

final class ServiceConfigException extends RuntimeException {
    final HttpStatus status;
    final String code;
    final boolean retryable;

    ServiceConfigException(HttpStatus status, String code, String message, boolean retryable) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryable = retryable;
    }
}
