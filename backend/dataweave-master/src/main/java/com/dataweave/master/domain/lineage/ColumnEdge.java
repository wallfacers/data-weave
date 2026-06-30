package com.dataweave.master.domain.lineage;

/**
 * 列级血缘边（设计 §6 输出契约）。由 019 {@code SqlColumnLineageExtractor} 产出，018 本期定义并能写入。
 *
 * <p>本期 {@code recordTaskIo} 写入路径接受 {@code List<ColumnEdge>}（可空）；列映射的产生不在 018。
 * 写入 {@code (:Column)-[:DERIVES_FROM {taskDefId,transform}]->(:Column)}。
 */
public record ColumnEdge(
        TableRef srcTable, String srcCol,
        TableRef dstTable, String dstCol,
        Transform transform,
        Confidence confidence
) {}
