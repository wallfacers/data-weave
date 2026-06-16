package com.dataweave.master.application;

/**
 * 类目领域业务异常，带稳定错误码供接口层映射 HTTP 状态。
 */
public class CatalogException extends RuntimeException {

    /** 非空文件夹禁删 → 接口层映射 409。 */
    public static final String NOT_EMPTY = "CATALOG_NODE_NOT_EMPTY";
    /** 移动成环（移到自身或后代之下）→ 接口层映射 400。 */
    public static final String CYCLE = "CATALOG_CYCLE";
    /** 文件夹/资产不存在 → 接口层映射 404。 */
    public static final String NOT_FOUND = "CATALOG_NOT_FOUND";
    /** 标签名项目内重复 → 接口层映射 409。 */
    public static final String TAG_DUPLICATE = "CATALOG_TAG_DUPLICATE";
    /** 入参非法（如跨项目、空名）→ 接口层映射 400。 */
    public static final String INVALID = "CATALOG_INVALID";

    private final String code;

    public CatalogException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() { return code; }
}
