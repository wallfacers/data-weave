package com.dataweave.master.domain.lineage;

/**
 * 一条任务读写边（设计态，表级）。direction = READS | WRITES。
 *
 * @param modelVersion 仅 {@link Source#SCRIPT_MODEL} 边：产出模型版本（041 FR-015 可回溯）；其余通道 null
 */
public record IoEdge(
        TableRef table,
        Direction direction,
        Source source,          // AGENT / SQL_PARSED / FORM / SCRIPT_*
        Confidence confidence,  // CONFIRMED / UNVERIFIED / CONFLICT
        String modelVersion
) {
    /** 兼容构造器：既有非模型通道调用点无 modelVersion 概念。 */
    public IoEdge(TableRef table, Direction direction, Source source, Confidence confidence) {
        this(table, direction, source, confidence, null);
    }
}
