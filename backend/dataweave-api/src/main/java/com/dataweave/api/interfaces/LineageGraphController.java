package com.dataweave.api.interfaces;

import com.dataweave.api.infrastructure.ApiResponse;
import com.dataweave.master.application.LineageGraphService;
import com.dataweave.master.application.LineageQueryService;
import com.dataweave.master.lineage.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 多粒度血缘 REST（020 重设计）—— 基于 neo4j Cypher 变长路径。
 *
 * <p>图谱读写分离：写侧由 {@link LineageGraphService#recordDesignTimeIo} 继续服务
 * （待 018 迁移），读侧统一经 {@link LineageQueryService} → Cypher 变长路径。
 * Tenant/project 当前固定 1/1，预留 {@code TenantContext}。
 */
@RestController
@RequestMapping("/api/lineage")
public class LineageGraphController {

    private static final long TENANT = 1L;
    private static final long PROJECT = 1L;

    private final LineageGraphService lineageGraphService;
    private final LineageQueryService lineageQueryService;

    public LineageGraphController(LineageGraphService lineageGraphService,
                                   LineageQueryService lineageQueryService) {
        this.lineageGraphService = lineageGraphService;
        this.lineageQueryService = lineageQueryService;
    }

    // ─── 结构下钻（US1） ───────────────────────────────────────

    /** 数据源列表（三级树第一层）。 */
    @GetMapping("/datasources")
    public ApiResponse<List<GraphNodeView>> datasources(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.ok(lineageQueryService.datasources(TENANT, PROJECT, offset, limit));
    }

    /** 表的列列表（三级树第三层）。 */
    @GetMapping("/tables/{id}/columns")
    public ApiResponse<List<GraphNodeView>> columns(
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.ok(lineageQueryService.columns(TENANT, PROJECT, id, offset, limit));
    }

    // ─── 上游/下游 变长路径（US2） ──────────────────────────────

    /** 表上游。granularity=table（默认）仅 FLOWS_TO；=column 含 DERIVES_FROM。 */
    @GetMapping("/tables/{id}/upstream")
    public ApiResponse<LineageGraph> upstream(
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int depth,
            @RequestParam(defaultValue = "TABLE") String granularity) {
        GraphNodeView.Granularity g = parseGranularity(granularity);
        return ApiResponse.ok(lineageQueryService.upstream(TENANT, PROJECT, id, depth, g));
    }

    /** 表下游。 */
    @GetMapping("/tables/{id}/downstream")
    public ApiResponse<LineageGraph> downstream(
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int depth,
            @RequestParam(defaultValue = "TABLE") String granularity) {
        GraphNodeView.Granularity g = parseGranularity(granularity);
        return ApiResponse.ok(lineageQueryService.downstream(TENANT, PROJECT, id, depth, g));
    }

    /** 列上游（列级 DERIVES_FROM 流）。 */
    @GetMapping("/columns/{id}/upstream")
    public ApiResponse<LineageGraph> columnUpstream(
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int depth) {
        return ApiResponse.ok(lineageQueryService.columnUpstream(TENANT, PROJECT, id, depth));
    }

    /** 列下游。 */
    @GetMapping("/columns/{id}/downstream")
    public ApiResponse<LineageGraph> columnDownstream(
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int depth) {
        return ApiResponse.ok(lineageQueryService.columnDownstream(TENANT, PROJECT, id, depth));
    }

    // ─── 影响面（US2） ─────────────────────────────────────────

    /** 全下游可达集合（表+列）。 */
    @GetMapping("/impact/{nodeId}")
    public ApiResponse<ImpactResult> impact(
            @PathVariable String nodeId,
            @RequestParam(defaultValue = "0") int depth,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.ok(lineageQueryService.impact(TENANT, PROJECT, nodeId, depth, offset, limit));
    }

    // ─── 指标血缘 + 运行态（US3） ───────────────────────────────

    /** 指标血缘：COMPUTED_FROM 的表/列。 */
    @GetMapping("/metrics/{id}/lineage")
    public ApiResponse<MetricLineage> metricLineage(@PathVariable String id) {
        return ApiResponse.ok(lineageQueryService.metricLineage(TENANT, PROJECT, id));
    }

    /** 今日同步行数。 */
    @GetMapping("/sync-summary")
    public ApiResponse<SyncSummary> syncSummary() {
        return ApiResponse.ok(lineageQueryService.syncSummary(TENANT, PROJECT));
    }

    // ─── 兼容旧端点（过渡期保留，走新实现） ─────────────────────

    /** 全局图（兼容旧端点 → 数据源列表 + 兜底）。 */
    @GetMapping("/graph")
    public ApiResponse<LineageGraph> graph() {
        return ApiResponse.ok(LineageGraph.empty());
    }

    /** N 跳邻域（兼容旧端点 → 影响面替代）。 */
    @GetMapping("/tables/{id}/neighborhood")
    public ApiResponse<LineageGraph> neighborhood(
            @PathVariable String id,
            @RequestParam(defaultValue = "2") int depth) {
        // 邻域 = 上游 ∪ 下游（有界）
        var d = LineageQueryService.clampDepth(depth);
        var up = lineageQueryService.upstream(TENANT, PROJECT, id, d, GraphNodeView.Granularity.TABLE);
        var down = lineageQueryService.downstream(TENANT, PROJECT, id, d, GraphNodeView.Granularity.TABLE);
        var allNodes = new java.util.ArrayList<GraphNodeView>();
        allNodes.addAll(up.nodes());
        allNodes.addAll(down.nodes());
        return ApiResponse.ok(new LineageGraph(
                allNodes.stream().distinct().toList(), List.of(),
                GraphNodeView.Granularity.TABLE, d,
                up.truncated() || down.truncated(),
                up.truncated() ? up.truncatedAt() : down.truncatedAt()));
    }

    // ─── 辅助 ──────────────────────────────────────────────────

    private static GraphNodeView.Granularity parseGranularity(String g) {
        if ("column".equalsIgnoreCase(g)) return GraphNodeView.Granularity.COLUMN;
        return GraphNodeView.Granularity.TABLE;
    }
}
