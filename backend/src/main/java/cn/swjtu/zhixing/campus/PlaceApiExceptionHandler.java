package cn.swjtu.zhixing.campus;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 地点接口错误映射：错误体统一为 {code, message}。 */
@RestControllerAdvice
public class PlaceApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(PlaceApiExceptionHandler.class);

    @ExceptionHandler(PlaceNotFoundException.class)
    public ResponseEntity<ApiError> handlePlaceNotFound(PlaceNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("PLACE_NOT_FOUND", exception.getMessage()));
    }

    @ExceptionHandler(InvalidScopeException.class)
    public ResponseEntity<ApiError> handleInvalidScope(InvalidScopeException exception) {
        return ResponseEntity.badRequest()
                .body(new ApiError("INVALID_SCOPE", exception.getMessage()));
    }

    @ExceptionHandler(OnlinePlaceException.class)
    public ResponseEntity<Map<String, Object>> handleOnlinePlace(OnlinePlaceException exception) {
        return ResponseEntity.status(exception.status()).body(Map.of(
                "code", exception.code(), "message", exception.getMessage(), "retryable", exception.retryable()));
    }

    @ExceptionHandler(PlaceDataLoadException.class)
    public ResponseEntity<ApiError> handleDataLoad(PlaceDataLoadException exception) {
        log.error("地点数据加载失败：{}", exception.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("PLACE_DATA_LOAD_FAILED", exception.getMessage()));
    }
}
