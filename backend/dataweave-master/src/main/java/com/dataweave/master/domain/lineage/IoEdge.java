package com.dataweave.master.domain.lineage;

/** 一条任务读写边（设计态，表级）。direction = READS | WRITES。 */
public record IoEdge(
        TableRef table,
        Direction direction,
        Source source,          // AGENT / SQL_PARSED / FORM
        Confidence confidence   // CONFIRMED / UNVERIFIED / CONFLICT
) {}
