package com.dataweave.api.interfaces;

import com.dataweave.api.infrastructure.ApiResponse;
import com.dataweave.api.infrastructure.TenantContext;
import com.dataweave.master.application.LineageQueryService;
import com.dataweave.master.i18n.BizException;
import com.dataweave.master.lineage.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 多粒度血缘 REST（020 重设计）—— 基于 neo4j Cypher 变长路径。
 *
 * <p>读写均经 {@link LineageQueryService}（neo4j 读侧）；写侧由 018 {@code LineageStore} 落图。
 *
 * <p>036 FR-013：移除硬编码 {@code 1L/1L}，{@code (tenantId, projectId)} 从请求上下文解析——
 * tenantId 取 {@link TenantContext}（JwtAuthFilter 注入），projectId 优先取 TenantContext
 * （地基注入并校验成员归属），回退到 {@code projectId} 查询参数（地基未注入前的兼容形态），
 * 二者皆空 → {@code project.required}。查询按项目作用域，杜绝跨项目血缘串号。
 */
@RestController
@RequestMapping("/api/lineage")
public class LineageGraphController {

    private final LineageQueryService lineageQueryService;

    public LineageGraphController(LineageQueryService lineageQueryService) {
        this.lineageQueryService = lineageQueryService;
    }

    // ─── 结构下钻（US1） ───────────────────────────────────────

    /** 数据源列表（三级树第一层）。 */
    @GetMapping("/datasources")
    public ApiResponse<List<GraphNodeView>> datasources(
            @RequestParam(required = false) Long projectId,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.ok(lineageQueryService.datasources(tenant(), project(projectId), offset, limit));
    }

    /** 表的列列表（三级树第三层）。 */
    @GetMapping("/tables/{id}/columns")
    public ApiResponse<List<GraphNodeView>> columns(
            @RequestParam(required = false) Long projectId,
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.ok(lineageQueryService.columns(tenant(), project(projectId), id, offset, limit));
    }

    // ─── 上游/下游 变长路径（US2） ──────────────────────────────

    /** 表上游。granularity=table（默认）仅 FLOWS_TO；=column 含 DERIVES_FROM。 */
    @GetMapping("/tables/{id}/upstream")
    public ApiResponse<LineageGraph> upstream(
            @RequestParam(required = false) Long projectId,
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int depth,
            @RequestParam(defaultValue = "TABLE") String granularity) {
        GraphNodeView.Granularity g = parseGranularity(granularity);
        return ApiResponse.ok(lineageQueryService.upstream(tenant(), project(projectId), id, depth, g));
    }

    /** 表下游。 */
    @GetMapping("/tables/{id}/downstream")
    public ApiResponse<LineageGraph> downstream(
            @RequestParam(required = false) Long projectId,
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int depth,
            @RequestParam(defaultValue = "TABLE") String granularity) {
        GraphNodeView.Granularity g = parseGranularity(granularity);
        return ApiResponse.ok(lineageQueryService.downstream(tenant(), project(projectId), id, depth, g));
    }

    /** 列上游（列级 DERIVES_FROM 流）。 */
    @GetMapping("/columns/{id}/upstream")
    public ApiResponse<LineageGraph> columnUpstream(
            @RequestParam(required = false) Long projectId,
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int depth) {
        return ApiResponse.ok(lineageQueryService.columnUpstream(tenant(), project(projectId), id, depth));
    }

    /** 列下游。 */
    @GetMapping("/columns/{id}/downstream")
    public ApiResponse<LineageGraph> columnDownstream(
            @RequestParam(required = false) Long projectId,
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int depth) {
        return ApiResponse.ok(lineageQueryService.columnDownstream(tenant(), project(projectId), id, depth));
    }

    // ─── 影响面（US2） ─────────────────────────────────────────

    /** 全下游可达集合（表+列）。 */
    @GetMapping("/impact/{nodeId}")
    public ApiResponse<ImpactResult> impact(
            @RequestParam(required = false) Long projectId,
            @PathVariable String nodeId,
            @RequestParam(defaultValue = "0") int depth,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.ok(lineageQueryService.impact(tenant(), project(projectId), nodeId, depth, offset, limit));
    }

    // ─── 指标血缘 + 运行态（US3） ───────────────────────────────

    /** 指标血缘：COMPUTED_FROM 的表/列。 */
    @GetMapping("/metrics/{id}/lineage")
    public ApiResponse<MetricLineage> metricLineage(
            @RequestParam(required = false) Long projectId,
            @PathVariable String id) {
        return ApiResponse.ok(lineageQueryService.metricLineage(tenant(), project(projectId), "ATOMIC", Long.parseLong(id)));
    }

    /** 今日同步行数。 */
    @GetMapping("/sync-summary")
    public ApiResponse<SyncSummary> syncSummary(@RequestParam(required = false) Long projectId) {
        return ApiResponse.ok(lineageQueryService.syncSummary(tenant(), project(projectId)));
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
            @RequestParam(required = false) Long projectId,
            @PathVariable String id,
            @RequestParam(defaultValue = "2") int depth) {
        // 邻域 = 上游 ∪ 下游（有界）
        long t = tenant();
        long p = project(projectId);
        var d = LineageQueryService.clampDepth(depth);
        var up = lineageQueryService.upstream(t, p, id, d, GraphNodeView.Granularity.TABLE);
        var down = lineageQueryService.downstream(t, p, id, d, GraphNodeView.Granularity.TABLE);
        var allNodes = new java.util.ArrayList<GraphNodeView>();
        allNodes.addAll(up.nodes());
        allNodes.addAll(down.nodes());
        return ApiResponse.ok(new LineageGraph(
                allNodes.stream().distinct().toList(), List.of(),
                GraphNodeView.Granularity.TABLE, d,
                up.truncated() || down.truncated(),
                up.truncated() ? up.truncatedAt() : down.truncatedAt()));
    }

    // ─── 上下文解析助手 ────────────────────────────────────────

    /** tenantId：JwtAuthFilter 已注入（含全栈测试），直接取。 */
    private static long tenant() {
        Long tid = TenantContext.tenantId();
        if (tid == null || tid <= 0) {
            throw new BizException("project.required");
        }
        return tid;
    }

    /** projectId：优先 TenantContext（地基注入、已校验成员归属），回退查询参数；皆空 → project.required。 */
    private static long project(Long requestParam) {
        Long fromContext = TenantContext.projectId();
        Long pid = fromContext != null ? fromContext : requestParam;
        if (pid == null || pid <= 0) {
            throw new BizException("project.required");
        }
        return pid;
    }

    private static GraphNodeView.Granularity parseGranularity(String g) {
        if ("column".equalsIgnoreCase(g)) return GraphNodeView.Granularity.COLUMN;
        return GraphNodeView.Granularity.TABLE;
    }
}
