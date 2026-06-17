package com.dataweave.api.infrastructure;

import com.dataweave.master.i18n.BizException;
import com.dataweave.master.i18n.Messages;
import java.util.Locale;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

/**
 * 全局异常处理器 —— 所有未捕获异常统一包装为 {@link ApiResponse}。
 *
 * <p>REST 层为 WebFlux（{@code spring.main.web-application-type=reactive}），异常方法注入
 * {@link ServerWebExchange} 取 {@code Accept-Language} 解析 UI locale。
 *
 * <p>{@link BizException} 经 {@link Messages} 按 UI locale 本地化为 message，并回传稳定 {@code errorCode}
 * 供前端据码做特殊 UI（{@link BizException} 子类如 CatalogException 自动复用此 handler）。
 * 其余异常的固定兜底文案（请求处理失败 / 服务器内部错误）亦按 UI locale 本地化；尚未迁移为
 * {@code BizException} 的异常（IllegalArgumentException 等）暂沿用其 message，待后续错误码迁移替换。
 *
 * <p>HTTP 状态码统一 200，业务状态通过 {@code code} 字段表达。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final Messages messages;

    public GlobalExceptionHandler(Messages messages) {
        this.messages = messages;
    }

    /** 业务异常：code → 本地化 message（按 UI locale）+ 稳定 errorCode。 */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBiz(BizException e, ServerWebExchange exchange) {
        Locale locale = Locales.uiLocale(exchange.getRequest().getHeaders());
        String message = messages.get(e.getCode(), locale, e.getArgs());
        return ResponseEntity.ok(ApiResponse.err(e.getHttpStatus(), e.getCode(), message));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.ok(ApiResponse.err(400, e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalState(IllegalStateException e) {
        return ResponseEntity.ok(ApiResponse.err(409, e.getMessage()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> handleResponseStatus(ResponseStatusException e, ServerWebExchange exchange) {
        int code = e.getStatusCode().value();
        Locale locale = Locales.uiLocale(exchange.getRequest().getHeaders());
        String reason = e.getReason() != null ? e.getReason() : messages.get("common.request_failed", locale);
        return ResponseEntity.ok(ApiResponse.err(code, reason));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e, ServerWebExchange exchange) {
        Locale locale = Locales.uiLocale(exchange.getRequest().getHeaders());
        return ResponseEntity.ok(ApiResponse.err(500, messages.get("common.internal_error", locale)));
    }
}
