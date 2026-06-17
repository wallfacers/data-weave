package com.dataweave.master.i18n;

/**
 * 平台业务异常基类 —— 携带稳定 code（i18n key）+ 可插值参数 + HTTP 状态映射。
 *
 * <p>替代裸 {@code throw new XxxException("中文")}。{@code GlobalExceptionHandler} 捕获后经
 * {@link Messages} 把 code 按 UI locale 翻成本地化 message，并回传稳定 errorCode 供前端据码做特殊 UI。
 *
 * <p>code 命名 {@code <domain>.<semantic>}，必须与 {@code messages*.properties} 的 key 对应，
 * 稳定、不复用、不改语义。默认 HTTP 状态 400，非默认状态经 {@link #withHttpStatus(int)} 链式指定
 * （避免与 {@code (String, Object...)} 构造的 varargs 歧义）。
 *
 * <p>置于 master 模块使 master / api / all-in-one 下的 worker 均可抛出。
 */
public class BizException extends RuntimeException {

    private final String code;
    private final Object[] args;
    private int httpStatus = 400;

    /** 默认 HTTP 状态 400。 */
    public BizException(String code, Object... args) {
        super(code); // 异常 message 用 code，便于日志溯源；面向用户的展示文案由 handler 经 Messages 本地化
        this.code = code;
        this.args = args;
    }

    /** 链式指定 HTTP 状态：{@code throw new BizException("x", id).withHttpStatus(404);}。 */
    public BizException withHttpStatus(int httpStatus) {
        this.httpStatus = httpStatus;
        return this;
    }

    public String getCode() {
        return code;
    }

    public Object[] getArgs() {
        return args == null ? new Object[0] : args.clone();
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
