package com.dataweave.master.application.lineage;

/**
 * 表引用：规范化点分表名 + 可选数据源坐标提示。
 *
 * <p>{@code datasourceHint} 供 018 关联 {@code :Datasource} 节点；本特性（纯解析）通常留空。
 */
public record TableRef(String qualifiedName, String datasourceHint) {

    public static TableRef of(String qualifiedName) {
        return new TableRef(qualifiedName, null);
    }
}
