package com.dataweave.api.interfaces;

import com.dataweave.api.infrastructure.ApiResponse;
import com.dataweave.master.application.ActionRequest;
import com.dataweave.master.application.CatalogAssignService;
import com.dataweave.master.application.CatalogException;
import com.dataweave.master.application.GateResult;
import com.dataweave.master.application.GatedActionService;
import com.dataweave.master.application.WorkflowService;
import com.dataweave.master.application.WorkflowService.DagPayload;
import com.dataweave.master.application.WorkflowService.DagView;
import com.dataweave.master.application.WorkflowService.PageResult;
import com.dataweave.master.domain.WorkflowDef;
import org.springframework.web.bind.annotation.*;

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

    public WorkflowController(WorkflowService workflowService, CatalogAssignService catalogAssignService,
                              GatedActionService gatedActionService) {
        this.workflowService = workflowService;
        this.catalogAssignService = catalogAssignService;
        this.gatedActionService = gatedActionService;
    }

    /** 创建工作流草稿。 */
    @PostMapping
    public ApiResponse<WorkflowDef> create(@RequestBody WorkflowDef body) {
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
        if (body.containsKey("path")) {
            throw new CatalogException(CatalogException.INVALID, "path 为后端派生字段，禁止传入");
        }
        if (body.containsKey("catalogNodeId")) {
            Object v = body.get("catalogNodeId");
            Long nodeId = v != null ? ((Number) v).longValue() : null;
            catalogAssignService.assignWorkflow(id, nodeId);
        }
        return ApiResponse.ok();
    }

    /** 工作流详情。 */
    @GetMapping("/{id}")
    public ApiResponse<WorkflowDef> getById(@PathVariable Long id) {
        WorkflowDef wf = workflowService.getById(id).orElse(null);
        if (wf == null) {
            return ApiResponse.err(404, "工作流不存在: " + id);
        }
        return ApiResponse.ok(wf);
    }

    /** 编辑工作流（调度配置等）。 */
    @PutMapping("/{id}")
    public ApiResponse<WorkflowDef> update(@PathVariable Long id, @RequestBody WorkflowDef body) {
        return ApiResponse.ok(workflowService.update(id, body));
    }

    /** 软删除工作流。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> softDelete(@PathVariable Long id) {
        workflowService.softDelete(id);
        return ApiResponse.ok();
    }

    /** 下线：ONLINE → DRAFT。 */
    @PostMapping("/{id}/offline")
    public ApiResponse<WorkflowDef> offline(@PathVariable Long id) {
        return ApiResponse.ok(workflowService.offline(id));
    }

    /** 读取 DAG（节点 + 边，含乐观锁 version）。 */
    @GetMapping("/{id}/dag")
    public ApiResponse<DagView> readDag(@PathVariable Long id) {
        return ApiResponse.ok(workflowService.readDag(id));
    }

    /** 整图保存 DAG（对账 upsert + 软删差集；version 冲突返回 409）。 */
    @PutMapping("/{id}/dag")
    public ApiResponse<DagView> saveDag(@PathVariable Long id, @RequestBody DagPayload body) {
        return ApiResponse.ok(workflowService.saveDag(id, body));
    }

    /** 发布：无环校验 + 冻结快照 + 版本自增。环路返回错误。 */
    @PostMapping("/{id}/publish")
    public ApiResponse<WorkflowDef> publish(@PathVariable Long id,
                                            @RequestBody(required = false) Map<String, String> body) {
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
                                       @RequestBody(required = false) RunRequest body) {
        WorkflowDef wf = workflowService.getById(id).orElse(null);
        if (wf == null) {
            return ApiResponse.err(404, "工作流不存在: " + id);
        }
        if (!"ONLINE".equals(wf.getStatus())) {
            return ApiResponse.err(409, "工作流未上线，需先发布再运行");
        }
        ActionRequest req = ActionRequest.builder()
                .toolName("trigger_workflow").actionType("TRIGGER_WORKFLOW")
                .targetType("WORKFLOW").targetId(String.valueOf(id))
                .command(body != null ? body.bizDate() : null)
                .actor("ui").actorSource("UI")
                .summary("手动触发工作流 #" + id + "「" + wf.getName() + "」")
                .build();
        return ApiResponse.ok(gatedActionService.submit(req));
    }

    /** 手动运行请求体（bizDate 可空）。 */
    public record RunRequest(String bizDate) {
    }
}
