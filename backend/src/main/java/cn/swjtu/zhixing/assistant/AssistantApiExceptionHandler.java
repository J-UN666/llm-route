package cn.swjtu.zhixing.assistant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 模型自检、问路及会话接口的 HTTP 错误映射。
 *
 * <p>所有错误体都带 requestId 与 retryable：400/422 不重试，502/504 与内部错误可重试。</p>
 */
@RestControllerAdvice(assignableTypes = {ToolCheckController.class, AssistantController.class})
public class AssistantApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AssistantApiExceptionHandler.class);

    @ExceptionHandler(AssistantApiException.class)
    public ResponseEntity<AssistantError> handleAssistantApiException(AssistantApiException ex) {
        log.info("assistant requestId={} status={} code={}", ex.requestId(), ex.status().value(), ex.code());
        return ResponseEntity.status(ex.status()).cacheControl(CacheControl.noStore())
                .body(new AssistantError(ex.requestId(), ex.code(), ex.getMessage(), ex.retryable()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, HttpMediaTypeNotSupportedException.class})
    public ResponseEntity<AssistantError> handleUnreadableBody(Exception ex) {
        String requestId = AssistantIds.requestId();
        log.debug("工具调用请求体无法解析 requestId={}", requestId);
        return ResponseEntity.badRequest()
                .body(new AssistantError(requestId, AssistantErrorCodes.INVALID_PARAMETER,
                        "请求体不是合法的 JSON，或字段类型不正确", false));
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<AssistantError> handleDatabaseFailure(Exception ex) {
        String requestId = AssistantIds.requestId();
        log.warn("会话数据库操作失败 requestId={} type={}", requestId, ex.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore())
                .body(new AssistantError(requestId, "CONVERSATION_STORE_UNAVAILABLE",
                        "会话暂时无法保存或恢复，请重试，也可使用普通表单", true));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<AssistantError> handleUnexpected(Exception ex) {
        String requestId = AssistantIds.requestId();
        log.error("问路服务内部错误 requestId={} type={}", requestId, ex.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new AssistantError(requestId, AssistantErrorCodes.SERVER_ERROR,
                        "模型工具调用服务内部错误，请稍后重试", true));
    }
}
