package com.dataweave.master.application.lineage;

import java.util.Optional;

/**
 * 解析期列元数据来源（019 ↔ 018 接缝契约）。
 *
 * <p>由 018 用 neo4j 里已注册的 {@code :Table}/{@code :Column} 元数据适配实现；本特性纯单测用
 * fixture 实现。列溯源必须知道源表有哪些列（尤其 {@code SELECT *} 展开、同名列消歧）。
 *
 * <p>查不到（{@link Optional#empty()}）即触发列级降级，绝不抛异常。
 */
public interface ColumnLineageCatalog {

    /** 查某表的列元数据；缺失返回 {@link Optional#empty()}。 */
    Optional<TableSchema> lookupTable(String qualifiedName);

    /** 空 catalog：任何表都查不到，迫使全降级。 */
    ColumnLineageCatalog EMPTY = qualifiedName -> Optional.empty();
}
