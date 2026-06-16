package com.dataweave.api.infrastructure;

import com.dataweave.master.application.CatalogException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * 全局异常处理器 —— 所有未捕获的异常统一包装为 {@link ApiResponse} 格式。
 *
 * <p>HTTP 状态码统一 200，业务状态码通过 code 字段表达。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.ok(ApiResponse.err(400, e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalState(IllegalStateException e) {
        return ResponseEntity.ok(ApiResponse.err(409, e.getMessage()));
    }

    /** 类目领域异常 → 按稳定错误码映射：非空禁删/标签重复 409、不存在 404、成环/非法 400。 */
    @ExceptionHandler(CatalogException.class)
    public ResponseEntity<ApiResponse<Void>> handleCatalog(CatalogException e) {
        int code = switch (e.getCode()) {
            case CatalogException.NOT_EMPTY, CatalogException.TAG_DUPLICATE -> 409;
            case CatalogException.NOT_FOUND -> 404;
            default -> 400; // CYCLE / INVALID
        };
        return ResponseEntity.ok(ApiResponse.err(code, e.getCode() + ": " + e.getMessage()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> handleResponseStatus(ResponseStatusException e) {
        int code = e.getStatusCode().value();
        String reason = e.getReason() != null ? e.getReason() : "请求处理失败";
        return ResponseEntity.ok(ApiResponse.err(code, reason));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        return ResponseEntity.ok(ApiResponse.err(500, "服务器内部错误"));
    }
}
