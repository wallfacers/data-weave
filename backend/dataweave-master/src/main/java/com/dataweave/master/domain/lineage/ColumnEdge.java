package com.dataweave.master.domain.lineage;

/**
 * 列级血缘边（设计 §6 输出契约）。由 019 {@code SqlColumnLineageExtractor} 产出，018 本期定义并能写入。
 *
 * <p>本期 {@code recordTaskIo} 写入路径接受 {@code List<ColumnEdge>}（可空）；列映射的产生不在 018。
 * 写入 {@code (:Column)-[:DERIVES_FROM {taskDefId,transform,confidence,source?}]->(:Column)}。
 *
 * @param source 来源通道（041 脚本通道填 SCRIPT_*；既有 SQL 任务路径为 null → 写入时省略该属性）
 */
public record ColumnEdge(
        TableRef srcTable, String srcCol,
        TableRef dstTable, String dstCol,
        Transform transform,
        Confidence confidence,
        Source source
) {
    /** 兼容构造器：既有 SQL 任务列级路径无 source 概念。 */
    public ColumnEdge(TableRef srcTable, String srcCol, TableRef dstTable, String dstCol,
                      Transform transform, Confidence confidence) {
        this(srcTable, srcCol, dstTable, dstCol, transform, confidence, null);
    }
}
