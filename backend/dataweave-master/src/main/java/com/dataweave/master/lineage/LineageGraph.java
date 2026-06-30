package com.dataweave.master.lineage;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 血缘子图载荷 —— 上下游/邻域查询的通用返回。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LineageGraph(
        /** 子图节点（去重）。 */
        List<GraphNodeView> nodes,
        /** 子图边。 */
        List<FlowEdgeView> edges,
        /** 本次查询粒度。 */
        GraphNodeView.Granularity granularity,
        /** 实际生效深度（clamp 后）。 */
        int depth,
        /** 是否触上界截断。 */
        boolean truncated,
        /** 截断处的节点数/深度（truncated=false 时 null）。 */
        Integer truncatedAt) {

    public static LineageGraph empty() {
        return new LineageGraph(List.of(), List.of(), GraphNodeView.Granularity.TABLE, 0, false, null);
    }
}
