package com.dataweave.api.interfaces;

import com.dataweave.api.infrastructure.ApiResponse;
import com.dataweave.api.infrastructure.Locales;
import com.dataweave.api.infrastructure.ProjectAuthz;
import com.dataweave.master.application.ActionRequest;
import com.dataweave.master.application.CatalogAssignService;
import com.dataweave.master.application.CatalogException;
import com.dataweave.master.application.GateResult;
import com.dataweave.master.application.GatedActionService;
import com.dataweave.master.application.TriggerCommand;
import com.dataweave.master.application.WorkflowService;
import com.dataweave.master.application.WorkflowService.DagPayload;
import com.dataweave.master.application.WorkflowService.DagView;
import com.dataweave.master.application.WorkflowService.PageResult;
import com.dataweave.master.domain.WorkflowDef;
import com.dataweave.master.i18n.BizException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;

import java.util.List;
import java.util.Map;

/**
 * 工作流编排 REST 端点（workflow-canvas）。
 *
 * <p>工作流定义 CRUD + DAG 整图读写 + 发布。DAG 以 node_key 为稳定标识整图读写：
 * GET /dag 读图、PUT /dag 整图保存（对账 upsert + 软删差集）、POST /publish 发布冻结快照。
 */
@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {

    private final WorkflowService workflowService;
    private final CatalogAssignService catalogAssignService;
    private final GatedActionService gatedActionService;
    private final ProjectAuthz projectAuthz;

    public WorkflowController(WorkflowService workflowService, CatalogAssignService catalogAssignService,
                              GatedActionService gatedActionService,
                              ProjectAuthz projectAuthz) {
        this.workflowService = workflowService;
        this.catalogAssignService = catalogAssignService;
        this.gatedActionService = gatedActionService;
        this.projectAuthz = projectAuthz;
    }

    /** 创建工作流草稿。036-D：EDITOR+（workflow:manage），并按当前项目打戳（FR-042）。 */
    @PostMapping
    public ApiResponse<WorkflowDef> create(@RequestBody WorkflowDef body) {
        projectAuthz.requireCurrent("workflow:manage");
        body.setTenantId(com.dataweave.api.infrastructure.TenantContext.tenantId());
        body.setProjectId(com.dataweave.api.infrastructure.TenantContext.projectId());
        return ApiResponse.ok(workflowService.create(body));
    }

    /** 分页搜索工作流（可选类目/标签过滤；无新参数时行为与既有一致）。 */
    @GetMapping
    public ApiResponse<PageResult> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long catalogNodeId,
            @RequestParam(defaultValue = "false") boolean uncategorized,
            @RequestParam(required = false) Long tagId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(workflowService.search(keyword, status, catalogNodeId, uncategorized, tagId, page, size));
    }

    /**
     * 归类工作流：{@code {"catalogNodeId": null}} 清空归属、{@code {}}（字段缺失）不改、给值则归入。
     * {@code path} 字段一律拒收。
     */
    @PatchMapping("/{id}/catalog")
    public ApiResponse<Void> assignCatalog(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        requireWorkflowManage(id);
        if (body.containsKey("path")) {
            throw new CatalogException("catalog.path.derived");
        }
        if (body.containsKey("catalogNodeId")) {
            Object v = body.get("catalogNodeId");
            Long nodeId = v != null ? ((Number) v).longValue() : null;
            catalogAssignService.assignWorkflow(id, nodeId);
        }
        return ApiResponse.ok();
    }

    /** 工作流详情（含版本历史）。 */
    @GetMapping("/{id}")
    public ApiResponse<WorkflowService.WorkflowDetail> getById(@PathVariable Long id) {
        WorkflowService.WorkflowDetail detail = workflowService.getById(id).orElse(null);
        if (detail == null) {
            throw new BizException("workflow.not_found", id).withHttpStatus(404);
        }
        return ApiResponse.ok(detail);
    }

    /**
     * 工作流漂移（workflow-version-binding）：是否需要「重新晋级」+ 漂移节点明细（pinned→latest）。
     * 读侧计算不落库——drifted = 任务版本漂移（快照钉死版落后于任务最新发布版）或 DAG 草稿漂移（has_draft_change）。
     */
    @GetMapping("/{id}/drift")
    public ApiResponse<WorkflowService.DriftResult> drift(@PathVariable Long id) {
        return ApiResponse.ok(workflowService.computeDrift(id));
    }

    /** 编辑工作流（调度配置等）。036-D：EDITOR+（workflow:manage），按实体归属 projectId 授权。 */
    @PutMapping("/{id}")
    public ApiResponse<WorkflowDef> update(@PathVariable Long id, @RequestBody WorkflowDef body) {
        requireWorkflowManage(id);
        return ApiResponse.ok(workflowService.update(id, body));
    }

    /** 软删除工作流。036-D：EDITOR+（workflow:manage），按实体归属 projectId 授权。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> softDelete(@PathVariable Long id) {
        requireWorkflowManage(id);
        workflowService.softDelete(id);
        return ApiResponse.ok();
    }

    /** 下线：ONLINE → DRAFT。036-D：EDITOR+（workflow:manage），按实体归属 projectId 授权。 */
    @PostMapping("/{id}/offline")
    public ApiResponse<WorkflowDef> offline(@PathVariable Long id) {
        requireWorkflowManage(id);
        return ApiResponse.ok(workflowService.offline(id));
    }

    /** 回滚到历史版本（恢复为草稿，需手动再发布）。经闸门 L1 直执行 + agent_action 留痕。
     * 036-D：EDITOR+（workflow:manage），按实体归属 projectId 授权（闸门前置门）。 */
    @PostMapping("/{id}/rollback")
    public ApiResponse<GateResult> rollback(@PathVariable Long id,
                                            @RequestBody Map<String, Object> body,
                                            ServerWebExchange exchange) {
        requireWorkflowManage(id);
        WorkflowService.WorkflowDetail detail = workflowService.getById(id).orElse(null);
        if (detail == null) {
            throw new BizException("workflow.not_found", id).withHttpStatus(404);
        }
        Object vno = body.get("versionNo");
        if (vno == null) {
            throw new BizException("workflow.rollback.missing_version_no").withHttpStatus(400);
        }
        ActionRequest req = ActionRequest.builder()
                .toolName("rollback_workflow").actionType("ROLLBACK_WORKFLOW")
                .targetType("WORKFLOW").targetId(String.valueOf(id))
                .command(String.valueOf(vno))
                .actor("ui").actorSource("UI")
                .summary("回滚工作流 #" + id + "「" + detail.workflow().getName() + "」到 v" + vno)
                .build();
        return ApiResponse.ok(gatedActionService.submit(req,
                Locales.uiLocale(exchange.getRequest().getHeaders())));
    }

    /** 读取 DAG（节点 + 边，含乐观锁 version）。 */
    @GetMapping("/{id}/dag")
    public ApiResponse<DagView> readDag(@PathVariable Long id) {
        return ApiResponse.ok(workflowService.readDag(id));
    }

    /** 读取已发布版本 DAG 快照（运维查看线上拓扑）。仅 ONLINE 时有数据。 */
    @GetMapping("/{id}/published-dag")
    public ApiResponse<DagView> readPublishedDag(@PathVariable Long id) {
        return ApiResponse.ok(workflowService.readPublishedDag(id));
    }

    /** 整图保存 DAG（对账 upsert + 软删差集；version 冲突返回 409）。
     * 036-D：EDITOR+（workflow:manage），按实体归属 projectId 授权。 */
    @PutMapping("/{id}/dag")
    public ApiResponse<DagView> saveDag(@PathVariable Long id, @RequestBody DagPayload body) {
        requireWorkflowManage(id);
        return ApiResponse.ok(workflowService.saveDag(id, body));
    }

    /**
     * 草稿整体保存（save-draft-atomic）：配置 + DAG 在一次请求、同一事务内落库，
     * 替代前端两次独立 PUT（{@code /dag} + {@code /{id}}）造成的非原子保存。
     * DAG version 冲突仍返回 409，且回滚配置改动。{@code config} 可空（仅存图）。
     * 036-D：EDITOR+（workflow:manage），按实体归属 projectId 授权。
     */
    @PutMapping("/{id}/draft")
    public ApiResponse<DagView> saveDraft(@PathVariable Long id, @RequestBody SaveDraftRequest body) {
        requireWorkflowManage(id);
        return ApiResponse.ok(workflowService.saveDraft(id, body.config(), body.dag()));
    }

    /** 草稿整体保存请求体：{@code config} 走配置 patch（可空），{@code dag} 走整图保存。 */
    public record SaveDraftRequest(WorkflowDef config, DagPayload dag) {
    }

    /** 发布：无环校验 + 冻结快照 + 版本自增。环路返回错误。
     * 036-D：EDITOR+（workflow:manage），按实体归属 projectId 授权。 */
    @PostMapping("/{id}/publish")
    public ApiResponse<WorkflowDef> publish(@PathVariable Long id,
                                            @RequestBody(required = false) Map<String, String> body) {
        requireWorkflowManage(id);
        String remark = body != null ? body.get("remark") : null;
        return ApiResponse.ok(workflowService.publish(id, remark));
    }

    /**
     * 手动触发正式运行（manual-run-trigger）：对**已上线**工作流经闸门起一个 trigger_type=MANUAL 的
     * 正式 workflow_instance（薄包装 {@code WorkflowTriggerService.trigger(wf, "MANUAL", bizDate, null)}）。
     * 返回 {@link GateResult}：
     * <ul>
     *   <li>{@code EXECUTED} —— {@code data.resultInstanceId} 为新实例 id，前端接 DAG 事件流；</li>
     *   <li>{@code PENDING_APPROVAL} —— {@code data.actionId} 为审批单号，批准后才下发。</li>
     * </ul>
     * 未上线工作流在闸门前即拒（409）。
     */
    @PostMapping("/{id}/run")
    public ApiResponse<GateResult> run(@PathVariable Long id,
                                       @RequestBody(required = false) RunRequest body,
                                       ServerWebExchange exchange) {
        WorkflowDef wf = workflowService.getById(id).map(WorkflowService.WorkflowDetail::workflow).orElse(null);
        if (wf == null) {
            throw new BizException("workflow.not_found", id).withHttpStatus(404);
        }
        if (!"ONLINE".equals(wf.getStatus())) {
            throw new BizException("workflow.not_online").withHttpStatus(409);
        }
        String bizDate = body != null ? body.bizDate() : null;
        String scope = body != null ? body.scope() : null;
        String targetNodeKey = body != null ? body.targetNodeKey() : null;
        ActionRequest req = ActionRequest.builder()
                .toolName("trigger_workflow").actionType("TRIGGER_WORKFLOW")
                .targetType("WORKFLOW").targetId(String.valueOf(id))
                .command(TriggerCommand.encode(bizDate, scope, targetNodeKey))
                .actor("ui").actorSource("UI")
                .summary("手动触发工作流 #" + id + "「" + wf.getName() + "」")
                .build();
        return ApiResponse.ok(gatedActionService.submit(req, Locales.uiLocale(exchange.getRequest().getHeaders())));
    }

    /** 手动运行请求体（bizDate/scope/targetNodeKey 均可空；scope 缺省 FULL）。 */
    public record RunRequest(String bizDate, String scope, String targetNodeKey) {
    }

    /** 列出工作流的跨周期依赖。 */
    @GetMapping("/{id}/dependencies")
    public ApiResponse<List<WorkflowService.DependencyDto>> listDependencies(@PathVariable Long id) {
        return ApiResponse.ok(workflowService.listDependencies(id));
    }

    /** 新建跨周期依赖（自依赖合法；非自指做跨流环检测）。
     * 036-D：EDITOR+（workflow:manage），按实体归属 projectId 授权。 */
    @PostMapping("/{id}/dependencies")
    public ApiResponse<WorkflowService.DependencyDto> createDependency(@PathVariable Long id,
            @RequestBody WorkflowService.DependencyDto body) {
        requireWorkflowManage(id);
        return ApiResponse.ok(workflowService.createDependency(id, body));
    }

    /** 删除跨周期依赖（软删）。036-D：EDITOR+（workflow:manage），按实体归属 projectId 授权。 */
    @DeleteMapping("/{id}/dependencies/{depId}")
    public ApiResponse<Void> deleteDependency(@PathVariable Long id, @PathVariable Long depId) {
        requireWorkflowManage(id);
        workflowService.deleteDependency(id, depId);
        return ApiResponse.ok();
    }

    /**
     * 036-D：工作流定义写操作授权（FR-042 EDITOR+），按实体归属 projectId。
     * 防跨项目按 id 改删；工作流不存在时保持既有 404 语义。
     */
    private void requireWorkflowManage(Long workflowId) {
        Long pid = workflowService.getById(workflowId)
                .map(d -> d.workflow().getProjectId())
                .orElseThrow(() -> new BizException("workflow.not_found", workflowId).withHttpStatus(404));
        projectAuthz.require("workflow:manage", pid);
    }
}
