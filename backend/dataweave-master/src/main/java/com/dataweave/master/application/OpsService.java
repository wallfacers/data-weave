package com.dataweave.master.application;

import com.dataweave.master.domain.LogBus;
import com.dataweave.master.domain.TaskDef;
import com.dataweave.master.domain.TaskDefRepository;
import com.dataweave.master.domain.TaskDiagnosis;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.TaskInstanceRepository;
import com.dataweave.master.domain.WorkflowInstance;
import com.dataweave.master.domain.WorkflowInstanceRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * 调度运维 / 驾驶舱查询服务：任务定义、运行实例、全局运行概况。
 */
@Service
public class OpsService {

    private final TaskDefRepository taskDefRepository;
    private final TaskInstanceRepository instanceRepository;
    private final WorkflowInstanceRepository workflowInstanceRepository;
    private final DiagnosisService diagnosisService;
    private final InstanceStateMachine stateMachine;
    private final LogBus logBus;

    public OpsService(TaskDefRepository taskDefRepository,
                      TaskInstanceRepository instanceRepository,
                      WorkflowInstanceRepository workflowInstanceRepository,
                      DiagnosisService diagnosisService,
                      InstanceStateMachine stateMachine,
                      LogBus logBus) {
        this.taskDefRepository = taskDefRepository;
        this.instanceRepository = instanceRepository;
        this.workflowInstanceRepository = workflowInstanceRepository;
        this.diagnosisService = diagnosisService;
        this.stateMachine = stateMachine;
        this.logBus = logBus;
    }

    /** 手动停止时向实例日志流插入的横幅（以 === 开头，前端 LogTab 会弱化着色，与执行 banner 一致）。 */
    private void appendManualStopLog(UUID taskInstanceId) {
        logBus.append(taskInstanceId, "=========== 手动停止 ===========");
        logBus.append(taskInstanceId, "状态: 已停止 | 操作: 用户手动停止运行");
    }

    /** 所有任务定义，按 id 升序。 */
    public List<TaskDef> tasks() {
        List<TaskDef> list = new ArrayList<>();
        taskDefRepository.findAll().forEach(list::add);
        list.sort(Comparator.comparing(TaskDef::getId, Comparator.nullsLast(Comparator.naturalOrder())));
        return list;
    }

    /**
     * 按 id 查单个任务实例（含 TEST 试跑）——日志流判定是否已结束用，不能用 {@link #instances()}（其排除 TEST）。
     */
    public java.util.Optional<TaskInstance> findInstance(UUID id) {
        return instanceRepository.findById(id);
    }

    /**
     * 正式运行实例（runMode=="NORMAL"，排除 TEST 试跑），按 id 降序。
     */
    public List<TaskInstance> instances() {
        return StreamSupport.stream(instanceRepository.findAll().spliterator(), false)
                .filter(i -> "NORMAL".equals(i.getRunMode()))
                .sorted(Comparator.comparing(TaskInstance::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());
    }

    /** 失败的正式运行实例（state==FAILED && runMode==NORMAL）。 */
    public List<TaskInstance> failedInstances() {
        return instanceRepository.findByState("FAILED").stream()
                .filter(i -> "NORMAL".equals(i.getRunMode()))
                .collect(Collectors.toList());
    }

    /**
     * 工作流实例详情：实例本身 + 其下任务节点（供实例详情视图渲染/操作）。
     * workflow_instance 恒为正式运行（runMode=NORMAL，试跑不建 workflow_instance）。
     */
    public WorkflowInstanceDetail workflowInstanceDetail(UUID id) {
        return workflowInstanceRepository.findById(id).map(wi -> {
            List<TaskNodeView> tasks = instanceRepository.findByWorkflowInstanceId(id).stream()
                    .map(ti -> new TaskNodeView(
                            ti.getId(),
                            ti.getTaskId(),
                            ti.getTaskId() != null
                                    ? taskDefRepository.findById(ti.getTaskId())
                                            .map(TaskDef::getName).orElse(null)
                                    : null,
                            ti.getState(),
                            ti.getWorkerNodeCode(),
                            ti.getAttempt()))
                    .toList();
            return new WorkflowInstanceDetail(
                    wi.getId(), wi.getWorkflowId(), wi.getBizDate(), wi.getState(),
                    wi.getPriority(), "NORMAL",
                    wi.getStartedAt() != null ? wi.getStartedAt().toString() : null,
                    wi.getFinishedAt() != null ? wi.getFinishedAt().toString() : null,
                    tasks);
        }).orElse(null);
    }

    /** 驾驶舱全局态势。 */
    public DashboardSummary summary() {
        List<TaskInstance> all = instances();
        int success = 0;
        int failed = 0;
        int running = 0;
        for (TaskInstance i : all) {
            String s = i.getState() == null ? "" : i.getState();
            switch (s) {
                case "SUCCESS" -> success++;
                case "FAILED" -> failed++;
                case "RUNNING" -> running++;
                default -> {
                }
            }
        }
        return new DashboardSummary(all.size(), success, failed, running,
                failedInstances(), diagnosisService.open());
    }

    // ─── 实例生命周期管理 ─────────────────────────────────

    /** 暂停工作流实例：RUNNING → PAUSED，所有 NOT_RUN 的 TaskInstance → PAUSED。 */
    public WorkflowInstance pauseWorkflow(UUID instanceId) {
        WorkflowInstance wi = workflowInstanceRepository.findById(instanceId)
                .orElseThrow(() -> new IllegalStateException("Instance not found: " + instanceId));
        if (!"RUNNING".equals(wi.getState())) {
            throw new IllegalStateException("Only RUNNING instances can be paused");
        }
        wi.setState("PAUSED");
        wi.setUpdatedAt(LocalDateTime.now());
        // 暂停所有 NOT_RUN 的 task instances
        instanceRepository.findByWorkflowInstanceId(instanceId).forEach(ti -> {
            if ("NOT_RUN".equals(ti.getState())) {
                ti.setState("PAUSED");
                ti.setUpdatedAt(LocalDateTime.now());
                instanceRepository.save(ti);
            }
        });
        return workflowInstanceRepository.save(wi);
    }

    /** 恢复工作流实例：PAUSED → RUNNING，所有 PAUSED 的 TaskInstance → NOT_RUN。 */
    public WorkflowInstance resumeWorkflow(UUID instanceId) {
        WorkflowInstance wi = workflowInstanceRepository.findById(instanceId)
                .orElseThrow(() -> new IllegalStateException("Instance not found: " + instanceId));
        if (!"PAUSED".equals(wi.getState())) {
            throw new IllegalStateException("Only PAUSED instances can be resumed");
        }
        wi.setState("RUNNING");
        wi.setUpdatedAt(LocalDateTime.now());
        instanceRepository.findByWorkflowInstanceId(instanceId).forEach(ti -> {
            if ("PAUSED".equals(ti.getState())) {
                ti.setState("NOT_RUN");
                ti.setUpdatedAt(LocalDateTime.now());
                instanceRepository.save(ti);
            }
        });
        return workflowInstanceRepository.save(wi);
    }

    /** 终止工作流实例：→ STOPPED，所有非终态 TaskInstance → STOPPED（经状态机 CAS 发状态事件→画布实时变色，并往各节点日志插手动停止行）。 */
    public WorkflowInstance killWorkflow(UUID instanceId) {
        WorkflowInstance wi = workflowInstanceRepository.findById(instanceId)
                .orElseThrow(() -> new IllegalStateException("Instance not found: " + instanceId));
        if (isTerminal(wi.getState())) {
            throw new IllegalStateException("Cannot kill a terminal instance");
        }
        LocalDateTime now = LocalDateTime.now();
        instanceRepository.findByWorkflowInstanceId(instanceId).forEach(ti -> {
            if (!isTerminal(ti.getState())) {
                // 经状态机 CAS 置 STOPPED：发布 dw:evt 状态事件（画布节点实时变红），并记 finished_at/归因
                if (stateMachine.casTaskTerminal(ti.getId(), ti.getState(), "STOPPED", "MANUAL_STOP")) {
                    appendManualStopLog(ti.getId());
                }
            }
        });
        // 工作流整体置 STOPPED（CAS 发布 workflowState 事件供实例详情视图）
        stateMachine.casWorkflowState(instanceId, wi.getState(), "STOPPED");
        wi.setState("STOPPED");
        wi.setFinishedAt(now);
        wi.setUpdatedAt(now);
        return workflowInstanceRepository.save(wi);
    }

    /** 暂停单个任务实例。 */
    public TaskInstance pauseTask(UUID instanceId) {
        TaskInstance ti = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new IllegalStateException("Task instance not found: " + instanceId));
        if (!"NOT_RUN".equals(ti.getState())) {
            throw new IllegalStateException("Only NOT_RUN task instances can be paused");
        }
        ti.setState("PAUSED");
        ti.setUpdatedAt(LocalDateTime.now());
        return instanceRepository.save(ti);
    }

    /** 恢复单个任务实例。 */
    public TaskInstance resumeTask(UUID instanceId) {
        TaskInstance ti = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new IllegalStateException("Task instance not found: " + instanceId));
        if (!"PAUSED".equals(ti.getState())) {
            throw new IllegalStateException("Only PAUSED task instances can be resumed");
        }
        ti.setState("NOT_RUN");
        ti.setUpdatedAt(LocalDateTime.now());
        return instanceRepository.save(ti);
    }

    /** 终止单个任务实例：经状态机 CAS 置 STOPPED（发状态事件），并往其日志流插手动停止行。 */
    public TaskInstance killTask(UUID instanceId) {
        TaskInstance ti = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new IllegalStateException("Task instance not found: " + instanceId));
        if (isTerminal(ti.getState())) {
            throw new IllegalStateException("Cannot kill a terminal task instance");
        }
        if (stateMachine.casTaskTerminal(instanceId, ti.getState(), "STOPPED", "MANUAL_STOP")) {
            appendManualStopLog(instanceId);
        }
        // 返回最新态（CAS 已落库）。
        return instanceRepository.findById(instanceId).orElse(ti);
    }

    /** 获取日志分块。 */
    public LogChunk getLog(UUID instanceId, int offset, int limit) {
        TaskInstance ti = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new IllegalStateException("Instance not found: " + instanceId));
        String log = ti.getLog();
        if (log == null || log.isEmpty()) {
            return new LogChunk("", 0, offset, false);
        }
        int totalSize = log.length();
        int end = Math.min(offset + limit, totalSize);
        String content = (offset < totalSize) ? log.substring(offset, end) : "";
        return new LogChunk(content, totalSize, offset, end < totalSize);
    }

    public record LogChunk(String content, int totalSize, int offset, boolean hasMore) {}

    public record TaskNodeView(UUID id, Long taskDefId, String taskDefName, String state,
            String workerNodeCode, Integer attempt) {}

    /** 工作流实例详情（实例 + 任务节点视图）。runMode 恒 NORMAL（试跑不建 workflow_instance）。 */
    public record WorkflowInstanceDetail(UUID id, Long workflowId, String bizDate, String state,
            Integer priority, String runMode, String startedAt, String finishedAt,
            List<TaskNodeView> tasks) {}

    private boolean isTerminal(String state) {
        return "SUCCESS".equals(state) || "FAILED".equals(state) || "STOPPED".equals(state);
    }

    /**
     * 驾驶舱概况。
     *
     * @param total           正式实例总数
     * @param success         成功数
     * @param failed          失败数
     * @param running         运行中数
     * @param failedInstances 失败实例清单
     * @param diagnosing      待处理（Agent 诊断中）的诊断清单
     */
    public record DashboardSummary(int total, int success, int failed, int running,
                                   List<TaskInstance> failedInstances, List<TaskDiagnosis> diagnosing) {
    }
}
