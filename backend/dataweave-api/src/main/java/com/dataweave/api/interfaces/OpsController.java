package com.dataweave.api.interfaces;

import com.dataweave.api.infrastructure.ApiResponse;
import com.dataweave.master.application.OpsService;
import com.dataweave.master.application.OpsService.DashboardSummary;
import com.dataweave.master.application.OpsService.LogChunk;
import com.dataweave.master.domain.TaskDef;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.WorkflowInstance;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 调度运维 / 驾驶舱查询 REST 端点：全局概况、任务定义、运行实例、失败清单。
 *
 * <p>供前端驾驶舱首页（{@code /}）、调度运维页（{@code /ops}）、数据开发页（{@code /tasks}）拉取。
 * MVP 阶段读侧走 REST，写侧（建任务/诊断/修复）统一走 Agent（{@code /agui}）。
 */
@RestController
@RequestMapping("/api/ops")
public class OpsController {

    private final OpsService opsService;

    public OpsController(OpsService opsService) {
        this.opsService = opsService;
    }

    /** 驾驶舱全局态势：计数 + 失败实例清单 + Agent 诊断中事项。 */
    @GetMapping("/summary")
    public ApiResponse<DashboardSummary> summary() {
        return ApiResponse.ok(opsService.summary());
    }

    /** 所有任务定义。 */
    @GetMapping("/tasks")
    public ApiResponse<List<TaskDef>> tasks() {
        return ApiResponse.ok(opsService.tasks());
    }

    /** 正式运行实例（排除 TEST 试跑），按 id 降序。 */
    @GetMapping("/instances")
    public ApiResponse<List<TaskInstance>> instances() {
        return ApiResponse.ok(opsService.instances());
    }

    /** 失败的正式运行实例。 */
    @GetMapping("/failed")
    public ApiResponse<List<TaskInstance>> failed() {
        return ApiResponse.ok(opsService.failedInstances());
    }

    // ─── 实例生命周期操作 ─────────────────────────────────

    @PostMapping("/instances/{id}/pause")
    public ApiResponse<?> pause(@PathVariable Long id) {
        return ApiResponse.ok(opsService.pauseWorkflow(id));
    }

    @PostMapping("/instances/{id}/resume")
    public ApiResponse<?> resume(@PathVariable Long id) {
        return ApiResponse.ok(opsService.resumeWorkflow(id));
    }

    @PostMapping("/instances/{id}/kill")
    public ApiResponse<?> kill(@PathVariable Long id) {
        return ApiResponse.ok(opsService.killWorkflow(id));
    }

    @PostMapping("/task-instances/{id}/pause")
    public ApiResponse<?> pauseTask(@PathVariable Long id) {
        return ApiResponse.ok(opsService.pauseTask(id));
    }

    @PostMapping("/task-instances/{id}/resume")
    public ApiResponse<?> resumeTask(@PathVariable Long id) {
        return ApiResponse.ok(opsService.resumeTask(id));
    }

    @PostMapping("/task-instances/{id}/kill")
    public ApiResponse<?> killTask(@PathVariable Long id) {
        return ApiResponse.ok(opsService.killTask(id));
    }

    @GetMapping("/instances/{id}/log")
    public ApiResponse<?> log(@PathVariable Long id,
                                 @RequestParam(defaultValue = "0") int offset,
                                 @RequestParam(defaultValue = "65536") int limit) {
        return ApiResponse.ok(opsService.getLog(id, offset, limit));
    }
}
