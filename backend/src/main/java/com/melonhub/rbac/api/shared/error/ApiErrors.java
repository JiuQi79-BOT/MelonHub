package com.melonhub.rbac.api.shared.error;

import com.melonhub.rbac.service.port.error.ApiException;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局 HTTP 异常映射器：将业务异常、参数校验失败与数据库约束冲突转换为统一 {@link ErrorBody} 响应。
 */
@RestControllerAdvice
public class ApiErrors {
    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

    public static ErrorBody body(String code, String message) {
        return ErrorBody.of(code, message);
    }

    public static ResponseEntity<ErrorBody> respond(ApiException e) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(e.status());
        if (e.retryAfterSeconds() != null) {
            response.header("Retry-After", String.valueOf(e.retryAfterSeconds()));
        }
        return response.body(body(e.code(), e.getMessage()));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorBody> api(ApiException e) {
        return respond(e);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorBody> integrity(DataIntegrityViolationException e) {
        int vendorCode = e.getMostSpecificCause() instanceof SQLException sql ? sql.getErrorCode() : 0;
        String message = switch (vendorCode) {
            case 1062 -> "编码或标识已存在，请更换后重试";
            case 1451 -> "存在仍在使用的关联数据，请先解除关联";
            case 1452 -> "关联的记录不存在或不属于当前隔离域";
            default -> "数据与现有记录冲突";
        };
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(body(vendorCode == 1062 ? "DUPLICATE" : "CONFLICT", message));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorBody> invalidBody(MethodArgumentNotValidException e) {
        FieldError field = e.getBindingResult().getFieldError();
        String message = field == null ? "请检查必填字段、长度和数据格式" : "字段 " + field.getField() + " 缺失或格式不正确";
        return ResponseEntity.badRequest().body(body("INVALID_REQUEST", message));
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            IllegalArgumentException.class,
            MissingRequestValueException.class,
            MethodArgumentTypeMismatchException.class,
            HandlerMethodValidationException.class
    })
    public ResponseEntity<ErrorBody> validation(Exception e) {
        return ResponseEntity.badRequest().body(body("INVALID_REQUEST", "请检查必填字段、长度和数据格式"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorBody> notFound(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body("NOT_FOUND", "接口不存在"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorBody> method(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(body("METHOD_NOT_ALLOWED", "接口不支持该请求方法"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorBody> mediaType(HttpMediaTypeNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(body("UNSUPPORTED_MEDIA_TYPE", "请求体须为 application/json"));
    }

    @ExceptionHandler({
            CannotGetJdbcConnectionException.class,
            DataAccessResourceFailureException.class,
            TransientDataAccessException.class,
            TransactionTimedOutException.class
    })
    public ResponseEntity<ErrorBody> overloaded(Exception e) {
        log.warn("Database temporarily unavailable or contended: {}", e.getClass().getSimpleName());
        return respond(ApiException.busy("SERVICE_BUSY", "服务繁忙，请稍后重试"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> unexpected(Exception e) {
        log.error("Unhandled request failure", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body("INTERNAL_ERROR", "服务内部错误，请联系管理员并提供请求编号"));
    }
}
