package com.dataweave.master.lineage;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 影响面结果 —— 某节点的全下游可达集合（{@code [:FLOWS_TO|DERIVES_FROM*]} 闭包）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ImpactResult(
        /** 起点节点。 */
        GraphNodeView root,
        /** 全下游可达集合（分层分粒度，含 type 区分表/列）。 */
        List<GraphNodeView> downstream,
        /** 支撑可达的边集合（供前端高亮路径）。 */
        List<FlowEdgeView> edges,
        /** 可达节点数。 */
        int nodeCount,
        /** 是否截断。 */
        boolean truncated,
        /** 截断点（truncated=false 时 null）。 */
        Integer truncatedAt) {
}
