package com.dataweave.master.domain.lineage;

/** 指标血缘边（迁现 metric_lineage）。downstream 指向表或列。 */
public record MetricEdge(
        long tenantId, long projectId,
        String metricType,      // ATOMIC / DERIVED
        long metricId,
        String metricName,
        String downstreamType,  // TABLE / COLUMN
        String downstreamRef    // 表限定名 / 列引用
) {}
