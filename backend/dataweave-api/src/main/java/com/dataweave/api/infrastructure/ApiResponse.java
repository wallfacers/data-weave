package com.dataweave.api.infrastructure;

/**
 * 统一 API 响应格式：{code, data, message}。
 *
 * <p>code=0 表示成功，非零表示业务错误（参照 HTTP 状态码语义：400/401/404/409/500 等）。
 * HTTP 状态码统一 200，业务状态通过 code 字段表达。
 */
public record ApiResponse<T>(int code, T data, String message) {

    /** 成功响应（无数据）。 */
    public static <T> ApiResponse<T> ok() {
        return new ApiResponse<>(0, null, "success");
    }

    /** 成功响应（带数据）。 */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, data, "success");
    }

    /** 失败响应。 */
    public static <T> ApiResponse<T> err(int code, String message) {
        return new ApiResponse<>(code, null, message);
    }
}
