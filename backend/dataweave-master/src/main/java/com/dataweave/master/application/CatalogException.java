package com.dataweave.master.application;

import com.dataweave.master.i18n.BizException;

/**
 * 类目领域业务异常 —— {@link BizException} 的具名子类，携带稳定 code（i18n key）+
 * 可插值参数 + HTTP 状态映射。
 *
 * <p>构造时按 code 自动 {@link #withHttpStatus(int) 设置} 对应 HTTP 状态：非空禁删 /
 * 标签重复 → 409、不存在 → 404、成环 / 非法 → 400。{@link
 * com.dataweave.api.infrastructure.GlobalExceptionHandler} 已统一处理 {@link BizException}，
 * 故本类无需单独 {@code @ExceptionHandler}。
 *
 * <p>常量名（{@link #NOT_EMPTY} 等）保留不变以兼容跨模块调用方（{@code TagService} /
 * {@code CatalogAssignService} / 各 Controller），仅值由 SCREAMING_SNAKE_CASE 迁为
 * {@code catalog.*} i18n key。{@code getMessage()} 返回 code 本身，便于日志溯源；
 * 面向用户的展示文案由 handler 经 {@code Messages} 本地化。
 */
public class CatalogException extends BizException {

    /** 非空文件夹禁删 → 409。 */
    public static final String NOT_EMPTY = "catalog.node.not_empty";
    /** 移动成环（移到自身或后代之下）→ 400。 */
    public static final String CYCLE = "catalog.cycle";
    /** 文件夹/资产不存在 → 404。 */
    public static final String NOT_FOUND = "catalog.not_found";
    /** 标签名项目内重复 → 409。 */
    public static final String TAG_DUPLICATE = "catalog.tag.duplicate";
    /** 入参非法（如跨项目、空名）→ 400。 */
    public static final String INVALID = "catalog.invalid";

    /**
     * 按 code 构造并自动映射对应 HTTP 状态。展示文案由 handler 经 {@code Messages} 按
     * {@code Accept-Language} 本地化，源码不再写中文展示串。
     *
     * @param code 稳定错误码（i18n key，{@code catalog.*}）
     * @param args 插值参数（对应 {@code messages*.properties} 的 {0} {1}…）
     */
    public CatalogException(String code, Object... args) {
        super(code, args);
        withHttpStatus(httpStatusFor(code));
    }

    private static int httpStatusFor(String code) {
        return switch (code) {
            case NOT_EMPTY, TAG_DUPLICATE -> 409;
            case NOT_FOUND -> 404;
            default -> 400; // CYCLE / INVALID / 其他未知 code 兜底 400
        };
    }
}
