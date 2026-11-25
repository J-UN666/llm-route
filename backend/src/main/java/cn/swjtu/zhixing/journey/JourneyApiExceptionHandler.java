package cn.swjtu.zhixing.journey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 普通行程和会合行程共用错误响应格式。
 *
 * <p>所有错误体都带 requestId 与 retryable：4xx 不重试，502/504 与内部错误可重试。</p>
 */
@RestControllerAdvice(assignableTypes = {
        JourneyController.class, DecisionMakingController.class, JourneyModesController.class
})
public class JourneyApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(JourneyApiExceptionHandler.class);

    @ExceptionHandler(JourneyApiException.class)
    public ResponseEntity<JourneyError> handleJourneyApiException(JourneyApiException ex) {
        return ResponseEntity.status(ex.status())
                .body(new JourneyError(ex.requestId(), ex.code(), ex.getMessage(), ex.retryable()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<JourneyError> handleUnreadableBody(HttpMessageNotReadableException ex) {
        String requestId = JourneyIds.requestId();
        log.debug("请求体无法解析 requestId={}", requestId, ex);
        return ResponseEntity.badRequest()
                .body(new JourneyError(requestId, "INVALID_PARAMETER",
                        "请求体不是合法的 JSON，或字段类型不正确", false));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<JourneyError> handleUnexpected(Exception ex) {
        String requestId = JourneyIds.requestId();
        log.error("行程查询出现未处理异常 requestId={}", requestId, ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new JourneyError(requestId, "SERVER_ERROR",
                        "行程服务内部错误，请稍后重试", true));
    }
}
