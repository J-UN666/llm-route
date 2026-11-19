package cn.swjtu.zhixing.serviceconfig;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
class ServiceConfigErrors {
    record ErrorBody(String code, String message, boolean retryable) {}

    @ExceptionHandler(ServiceConfigException.class)
    ResponseEntity<ErrorBody> error(ServiceConfigException error) {
        return ResponseEntity.status(error.status).cacheControl(CacheControl.noStore())
                .body(new ErrorBody(error.code, error.getMessage(), error.retryable));
    }
}
