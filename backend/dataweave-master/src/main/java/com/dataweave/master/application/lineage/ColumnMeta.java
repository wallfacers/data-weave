package com.dataweave.master.application.lineage;

/**
 * 列元数据。
 *
 * @param name     列名（规范化）
 * @param dataType 数据类型字面（可空；缺失时按 VARCHAR 处理）
 * @param ordinal  列序（0 基，用于 {@code SELECT *} 按序展开）
 */
public record ColumnMeta(String name, String dataType, int ordinal) {
}
