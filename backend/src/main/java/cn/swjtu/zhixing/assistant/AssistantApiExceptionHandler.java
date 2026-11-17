package cn.swjtu.zhixing.assistant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 模型工具调用接口错误映射，只作用于 {@link ToolCheckController}，不影响地点、接驳与行程接口。
 *
 * <p>所有错误体都带 requestId 与 retryable：400/422 不重试，502/504 与内部错误可重试。</p>
 */
@RestControllerAdvice(assignableTypes = ToolCheckController.class)
public class AssistantApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AssistantApiExceptionHandler.class);

    @ExceptionHandler(AssistantApiException.class)
    public ResponseEntity<AssistantError> handleAssistantApiException(AssistantApiException ex) {
        return ResponseEntity.status(ex.status())
                .body(new AssistantError(ex.requestId(), ex.code(), ex.getMessage(), ex.retryable()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<AssistantError> handleUnreadableBody(HttpMessageNotReadableException ex) {
        String requestId = AssistantIds.requestId();
        log.debug("工具调用请求体无法解析 requestId={}", requestId, ex);
        return ResponseEntity.badRequest()
                .body(new AssistantError(requestId, AssistantErrorCodes.INVALID_PARAMETER,
                        "请求体不是合法的 JSON，或字段类型不正确", false));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<AssistantError> handleUnexpected(Exception ex) {
        String requestId = AssistantIds.requestId();
        log.error("模型工具调用接口出现未处理异常 requestId={}", requestId, ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new AssistantError(requestId, AssistantErrorCodes.SERVER_ERROR,
                        "模型工具调用服务内部错误，请稍后重试", true));
    }
}
