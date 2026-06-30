package com.dataweave.master.domain.asset;

/**
 * 资产敏感度分级（FR-008）。可见性 = 租户/项目隔离 + 敏感度权限叠加。
 * 存库为 {@code data_asset.sensitivity} VARCHAR(32)；数据术语保留英文。
 */
public enum Sensitivity {
    PUBLIC,
    INTERNAL,
    CONFIDENTIAL,
    PII;

    public static Sensitivity parseOrDefault(String s) {
        if (s == null || s.isBlank()) return INTERNAL;
        try {
            return valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return INTERNAL;
        }
    }
}
