package com.dataweave.api.interfaces;

import com.dataweave.master.application.OpsService;
import com.dataweave.master.application.OpsService.DashboardSummary;
import com.dataweave.master.domain.TaskDef;
import com.dataweave.master.domain.TaskInstance;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
