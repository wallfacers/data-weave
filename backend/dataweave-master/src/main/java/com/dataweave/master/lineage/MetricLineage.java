package com.dataweave.master.lineage;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 指标血缘 —— 指标由哪些表/列计算。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MetricLineage(
        /** 指标节点（type=METRIC）。 */
        GraphNodeView metric,
        /** COMPUTED_FROM 指向的表/列。 */
        List<GraphNodeView> sources,
        /** metric → source 边。 */
        List<FlowEdgeView> edges) {
}
