package com.dataweave.api.interfaces;

import com.dataweave.api.infrastructure.ApiResponse;
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

    public WorkflowController(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    /** 创建工作流草稿。 */
    @PostMapping
    public ApiResponse<WorkflowDef> create(@RequestBody WorkflowDef body) {
        return ApiResponse.ok(workflowService.create(body));
    }

    /** 分页搜索工作流。 */
    @GetMapping
    public ApiResponse<PageResult> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(workflowService.search(keyword, status, page, size));
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
}
