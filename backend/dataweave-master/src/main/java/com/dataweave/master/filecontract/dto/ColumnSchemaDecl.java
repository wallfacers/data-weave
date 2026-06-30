package com.dataweave.master.filecontract.dto;

/**
 * 声明的单个列 schema（来自 .task.yaml schema 块）。
 *
 * @param name 列名
 * @param type SQL 类型文本（如 BIGINT/DECIMAL(18,2)）
 */
public record ColumnSchemaDecl(String name, String type) {
}
