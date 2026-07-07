package com.dataweave.api.interfaces;

import com.dataweave.api.infrastructure.ApiResponse;
import com.dataweave.api.infrastructure.TenantContext;
import com.dataweave.master.application.LineageQueryService;
import com.dataweave.master.i18n.BizException;
import com.dataweave.master.lineage.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 多粒度血缘 REST（020 重设计 + 052 查询补齐）—— 基于 neo4j Cypher 变长路径。
 *
 * <p>读写均经 {@link LineageQueryService}（neo4j 读侧）；写侧由 018 {@code LineageStore} 落图。
 *
 * <p>036 FR-013：移除硬编码 {@code 1L/1L}，{@code (tenantId, projectId)} 从请求上下文解析——
 * tenantId 取 {@link TenantContext}（JwtAuthFilter 注入），projectId 优先取 TenantContext
 * （地基注入并校验成员归属），回退到 {@code projectId} 查询参数（地基未注入前的兼容形态），
 * 二者皆空 → {@code project.required}。查询按项目作用域，杜绝跨项目血缘串号。
 *
 * <p>052 扩展：/search、/paths 新端点；upstream/downstream/impact/neighborhood 加过滤参数；
 * neighborhood 双向带边（修复 List.of() 丢边）；全部只读不经 PolicyEngine。
 */
@RestController
@RequestMapping("/api/lineage")
public class LineageGraphController {

    private final LineageQueryService lineageQueryService;
    private final com.dataweave.master.domain.lineage.LineageHintRepository lineageHintRepository;
    private final com.dataweave.master.application.lineage.LineageCorrectionService lineageCorrectionService;
    private final com.dataweave.master.application.GatedActionService gatedActionService;
    private final com.dataweave.api.infrastructure.ProjectAuthz projectAuthz;
    private final tools.jackson.databind.ObjectMapper objectMapper = new tools.jackson.databind.ObjectMapper();

    public LineageGraphController(LineageQueryService lineageQueryService,
                                  com.dataweave.master.domain.lineage.LineageHintRepository lineageHintRepository,
                                  com.dataweave.master.application.lineage.LineageCorrectionService lineageCorrectionService,
                                  com.dataweave.master.application.GatedActionService gatedActionService,
                                  com.dataweave.api.infrastructure.ProjectAuthz projectAuthz) {
        this.lineageQueryService = lineageQueryService;
        this.lineageHintRepository = lineageHintRepository;
        this.lineageCorrectionService = lineageCorrectionService;
        this.gatedActionService = gatedActionService;
        this.projectAuthz = projectAuthz;
    }

    // ─── 041 脚本血缘：未解析提示（US2，FR-006） ────────────────

    /** 未解析提示视图。 */
    public record UnresolvedHintView(Long id, String kind, String scriptHint,
                                     Integer versionNo, java.time.LocalDateTime createdAt) {}

    /** 某任务的未解析提示（脚本中疑似读写但静态无法确定目标的点）。 */
    @GetMapping("/tasks/{taskDefId}/hints")
    public ApiResponse<List<UnresolvedHintView>> taskHints(
            @RequestParam(required = false) Long projectId,
            @PathVariable long taskDefId) {
        return ApiResponse.ok(lineageHintRepository
                .findByTenantIdAndProjectIdAndTaskDefIdOrderByIdAsc(tenant(), project(projectId), taskDefId)
                .stream()
                .map(h -> new UnresolvedHintView(h.getId(), h.getKind(), h.getScriptHint(),
                        h.getVersionNo(), h.getCreatedAt()))
                .toList());
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
            @RequestParam(defaultValue = "TABLE") String granularity,
            @RequestParam(required = false) String layers,
            @RequestParam(required = false) String types,
            @RequestParam(required = false) String confidences,
            @RequestParam(required = false) String sources) {
        GraphNodeView.Granularity g = parseGranularity(granularity);
        return ApiResponse.ok(lineageQueryService.upstream(tenant(), project(projectId), id, depth, g,
                LineageQueryService.parseFilterList(layers),
                LineageQueryService.parseFilterList(types),
                LineageQueryService.parseFilterList(confidences),
                LineageQueryService.parseFilterList(sources)));
    }

    /** 表下游。 */
    @GetMapping("/tables/{id}/downstream")
    public ApiResponse<LineageGraph> downstream(
            @RequestParam(required = false) Long projectId,
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int depth,
            @RequestParam(defaultValue = "TABLE") String granularity,
            @RequestParam(required = false) String layers,
            @RequestParam(required = false) String types,
            @RequestParam(required = false) String confidences,
            @RequestParam(required = false) String sources) {
        GraphNodeView.Granularity g = parseGranularity(granularity);
        return ApiResponse.ok(lineageQueryService.downstream(tenant(), project(projectId), id, depth, g,
                LineageQueryService.parseFilterList(layers),
                LineageQueryService.parseFilterList(types),
                LineageQueryService.parseFilterList(confidences),
                LineageQueryService.parseFilterList(sources)));
    }

    /** 列上游（列级 DERIVES_FROM 流）。 */
    @GetMapping("/columns/{id}/upstream")
    public ApiResponse<LineageGraph> columnUpstream(
            @RequestParam(required = false) Long projectId,
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int depth,
            @RequestParam(required = false) String confidences,
            @RequestParam(required = false) String sources) {
        return ApiResponse.ok(lineageQueryService.columnUpstream(tenant(), project(projectId), id, depth,
                LineageQueryService.parseFilterList(confidences),
                LineageQueryService.parseFilterList(sources)));
    }

    /** 列下游。 */
    @GetMapping("/columns/{id}/downstream")
    public ApiResponse<LineageGraph> columnDownstream(
            @RequestParam(required = false) Long projectId,
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int depth,
            @RequestParam(required = false) String confidences,
            @RequestParam(required = false) String sources) {
        return ApiResponse.ok(lineageQueryService.columnDownstream(tenant(), project(projectId), id, depth,
                LineageQueryService.parseFilterList(confidences),
                LineageQueryService.parseFilterList(sources)));
    }

    // ─── 影响面（US2） ─────────────────────────────────────────

    /** 全下游可达集合（表+列）。052：加过滤 + edges + reachableTotal。 */
    @GetMapping("/impact/{nodeId}")
    public ApiResponse<ImpactResult> impact(
            @RequestParam(required = false) Long projectId,
            @PathVariable String nodeId,
            @RequestParam(defaultValue = "0") int depth,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) String layers,
            @RequestParam(required = false) String types,
            @RequestParam(required = false) String confidences,
            @RequestParam(required = false) String sources) {
        return ApiResponse.ok(lineageQueryService.impact(tenant(), project(projectId), nodeId, depth, offset, limit,
                LineageQueryService.parseFilterList(layers),
                LineageQueryService.parseFilterList(types),
                LineageQueryService.parseFilterList(confidences),
                LineageQueryService.parseFilterList(sources)));
    }

    // ─── 052 双向邻域（US1 / FR-003/007） ──────────────────────

    /** N 跳邻域：双向带边（修复原 List.of() 丢边）。 */
    @GetMapping("/tables/{id}/neighborhood")
    public ApiResponse<LineageGraph> neighborhood(
            @RequestParam(required = false) Long projectId,
            @PathVariable String id,
            @RequestParam(defaultValue = "2") int depth,
            @RequestParam(defaultValue = "TABLE") String granularity,
            @RequestParam(required = false) String layers,
            @RequestParam(required = false) String types,
            @RequestParam(required = false) String confidences,
            @RequestParam(required = false) String sources) {
        GraphNodeView.Granularity g = parseGranularity(granularity);
        return ApiResponse.ok(lineageQueryService.neighborhood(tenant(), project(projectId), id, depth, g,
                LineageQueryService.parseFilterList(layers),
                LineageQueryService.parseFilterList(types),
                LineageQueryService.parseFilterList(confidences),
                LineageQueryService.parseFilterList(sources)));
    }

    // ─── 052 按名搜索（US2 / FR-008/009/011/022） ──────────────

    /** 按名搜索数据资产。 */
    @GetMapping("/search")
    public ApiResponse<List<SearchCandidate>> search(
            @RequestParam(required = false) Long projectId,
            @RequestParam String q,
            @RequestParam(required = false) String types,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.ok(lineageQueryService.search(tenant(), project(projectId), q,
                LineageQueryService.parseFilterList(types), offset, limit));
    }

    // ─── 052 两点间路径（US3 / FR-014） ────────────────────────

    /** 两节点间所有连接路径。 */
    @GetMapping("/paths")
    public ApiResponse<LineagePath> paths(
            @RequestParam(required = false) Long projectId,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(defaultValue = "0") int depth) {
        return ApiResponse.ok(lineageQueryService.pathsBetween(tenant(), project(projectId), from, to, depth));
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

    // ─── 041 脚本血缘：人工修正（US3，FR-007，经门禁 L1 + agent_action 留痕） ───

    /** 修正请求：action=CONFIRM|REMOVE|REVOKE + 血缘语义键。 */
    public record CorrectionRequest(String action, long taskDefId, String direction,
                                    String tableKey, String columnKey) {}

    /** 提交人工修正（项目管理权限；contracts §1）。 */
    @PostMapping("/corrections")
    public ApiResponse<java.util.Map<String, Object>> submitCorrection(
            @RequestParam(required = false) Long projectId,
            @RequestBody CorrectionRequest req,
            org.springframework.web.server.ServerWebExchange exchange) {
        long tenantId = tenant();
        long pid = project(projectId);
        projectAuthz.require("project:manage", pid);
        var locale = com.dataweave.api.infrastructure.Locales.uiLocale(exchange.getRequest().getHeaders());

        String actionType = switch (req.action() == null ? "" : req.action().toUpperCase()) {
            case "CONFIRM" -> "LINEAGE_EDGE_CONFIRM";
            case "REMOVE" -> "LINEAGE_EDGE_REMOVE";
            case "REVOKE" -> "LINEAGE_CORRECTION_REVOKE";
            default -> throw new BizException("lineage.correction_conflict", String.valueOf(req.action()));
        };
        String command = objectMapper.writeValueAsString(java.util.Map.of(
                "tenantId", tenantId, "projectId", pid, "taskDefId", req.taskDefId(),
                "direction", req.direction() == null ? "" : req.direction(),
                "tableKey", req.tableKey() == null ? "" : req.tableKey(),
                "columnKey", req.columnKey() == null ? "" : req.columnKey()));
        var actionReq = com.dataweave.master.application.ActionRequest.builder()
                .toolName(actionType).actionType(actionType)
                .targetType("LINEAGE_EDGE")
                .targetId(req.taskDefId() + "|" + req.direction() + "|" + req.tableKey())
                .command(command)
                .actor(String.valueOf(TenantContext.userId())).actorSource("UI")
                .summary(actionType + " " + req.tableKey())
                .build();
        var gr = gatedActionService.submit(actionReq, locale);

        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("outcome", gr.outcome().name());
        out.put("actionId", gr.actionId());
        if (!gr.executed()) {
            out.put("message", gr.message());
        }
        return ApiResponse.ok(out);
    }

    /** 某任务当前生效裁决列表（contracts §3）。 */
    @GetMapping("/tasks/{taskDefId}/corrections")
    public ApiResponse<List<com.dataweave.master.application.lineage.LineageCorrectionService.CorrectionView>>
    taskCorrections(@RequestParam(required = false) Long projectId, @PathVariable long taskDefId) {
        return ApiResponse.ok(lineageCorrectionService.listForTask(tenant(), project(projectId), taskDefId));
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
