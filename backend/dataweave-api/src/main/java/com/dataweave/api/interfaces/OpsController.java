package com.dataweave.api.interfaces;

import com.dataweave.master.application.OpsService;
import com.dataweave.master.application.OpsService.DashboardSummary;
import com.dataweave.master.application.OpsService.LogChunk;
import com.dataweave.master.domain.TaskDef;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.WorkflowInstance;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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
    public DashboardSummary summary() {
        return opsService.summary();
    }

    /** 所有任务定义。 */
    @GetMapping("/tasks")
    public List<TaskDef> tasks() {
        return opsService.tasks();
    }

    /** 正式运行实例（排除 TEST 试跑），按 id 降序。 */
    @GetMapping("/instances")
    public List<TaskInstance> instances() {
        return opsService.instances();
    }

    /** 失败的正式运行实例。 */
    @GetMapping("/failed")
    public List<TaskInstance> failed() {
        return opsService.failedInstances();
    }

    // ─── 实例生命周期操作 ─────────────────────────────────

    @PostMapping("/instances/{id}/pause")
    public ResponseEntity<?> pause(@PathVariable Long id) {
        try { return ResponseEntity.ok(opsService.pauseWorkflow(id)); }
        catch (IllegalStateException e) { return conflict(e); }
    }

    @PostMapping("/instances/{id}/resume")
    public ResponseEntity<?> resume(@PathVariable Long id) {
        try { return ResponseEntity.ok(opsService.resumeWorkflow(id)); }
        catch (IllegalStateException e) { return conflict(e); }
    }

    @PostMapping("/instances/{id}/kill")
    public ResponseEntity<?> kill(@PathVariable Long id) {
        try { return ResponseEntity.ok(opsService.killWorkflow(id)); }
        catch (IllegalStateException e) { return conflict(e); }
    }

    @PostMapping("/task-instances/{id}/pause")
    public ResponseEntity<?> pauseTask(@PathVariable Long id) {
        try { return ResponseEntity.ok(opsService.pauseTask(id)); }
        catch (IllegalStateException e) { return conflict(e); }
    }

    @PostMapping("/task-instances/{id}/resume")
    public ResponseEntity<?> resumeTask(@PathVariable Long id) {
        try { return ResponseEntity.ok(opsService.resumeTask(id)); }
        catch (IllegalStateException e) { return conflict(e); }
    }

    @PostMapping("/task-instances/{id}/kill")
    public ResponseEntity<?> killTask(@PathVariable Long id) {
        try { return ResponseEntity.ok(opsService.killTask(id)); }
        catch (IllegalStateException e) { return conflict(e); }
    }

    @GetMapping("/instances/{id}/log")
    public ResponseEntity<?> log(@PathVariable Long id,
                                 @RequestParam(defaultValue = "0") int offset,
                                 @RequestParam(defaultValue = "65536") int limit) {
        try { return ResponseEntity.ok(opsService.getLog(id, offset, limit)); }
        catch (IllegalStateException e) { return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage())); }
    }

    private ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
}
