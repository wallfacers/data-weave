package com.dataweave.master.lineage;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 两节点间所有连接路径的结果（US3 / FR-014）。
 *
 * <p>有界变长 {@code *1..depth}（clamp≤20）+ pathCap；Neo4j 默认关系不重复防环（FR-025）。
 * 无路径时 nodes/edges 为空 + pathExists=false。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LineagePath(
        /** 起点节点。 */
        GraphNodeView from,
        /** 终点节点。 */
        GraphNodeView to,
        /** 所有连接路径上的节点去重集。 */
        List<GraphNodeView> nodes,
        /** 路径上的边去重集（供高亮）。 */
        List<FlowEdgeView> edges,
        /** 是否有至少一条路径连接 from→to。 */
        boolean pathExists,
        /** 达 pathCap 截断。 */
        boolean truncated) {
}
