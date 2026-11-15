package cn.swjtu.zhixing.campus;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 校园接驳接口错误映射。
 *
 * <ul>
 *   <li>400 INVALID_ORIGIN_ID / INVALID_DEPARTURE_TIME；</li>
 *   <li>404 PLACE_NOT_FOUND 由 {@link PlaceApiExceptionHandler} 统一处理；</li>
 *   <li>422 NO_APPROVED_CONNECTOR：带每个未返回接驳的原因；</li>
 *   <li>500 CONNECTOR_DATA_LOAD_FAILED：接驳应用数据缺失或校验失败。</li>
 * </ul>
 */
@RestControllerAdvice
public class CampusConnectorApiExceptionHandler {

    @ExceptionHandler(InvalidOriginIdException.class)
    public ResponseEntity<ApiError> handleInvalidOriginId(InvalidOriginIdException ex) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_ORIGIN_ID", ex.getMessage()));
    }

    @ExceptionHandler(InvalidDepartureTimeException.class)
    public ResponseEntity<ApiError> handleInvalidDepartureTime(InvalidDepartureTimeException ex) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_DEPARTURE_TIME", ex.getMessage()));
    }

    @ExceptionHandler(NoApprovedConnectorException.class)
    public ResponseEntity<ConnectorErrorResponse> handleNoApprovedConnector(NoApprovedConnectorException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ConnectorErrorResponse("NO_APPROVED_CONNECTOR", ex.getMessage(), ex.originId(),
                        ex.originKind(), ex.dataVersion(), ex.unavailable()));
    }

    @ExceptionHandler(CampusConnectorDataLoadException.class)
    public ResponseEntity<ApiError> handleDataLoadFailure(CampusConnectorDataLoadException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("CONNECTOR_DATA_LOAD_FAILED", ex.getMessage()));
    }
}
