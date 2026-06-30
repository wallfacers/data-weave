package com.dataweave.master.application.lineage;

/**
 * 一条列级派生关系：{@code dstTable.dstCol ← srcTable.srcCol}。
 *
 * <p>019 ↔ 018/020 共享接缝契约的核心产物，详见
 * {@code specs/019-lineage-column-lineage/contracts/column-lineage-contract.md}。
 */
public record ColumnEdge(
        TableRef srcTable, String srcCol,
        TableRef dstTable, String dstCol,
        Transform transform,
        Confidence confidence) {
}
