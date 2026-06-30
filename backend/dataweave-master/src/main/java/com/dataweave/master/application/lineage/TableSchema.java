package com.dataweave.master.application.lineage;

import java.util.List;

/**
 * 解析期表元数据：规范化表名 + 有序列清单。
 *
 * <p>列顺序即物理列序，供 Calcite validator 解析列引用、展开 {@code *}、消歧同名列。
 */
public record TableSchema(String qualifiedName, List<ColumnMeta> columns) {
}
