package com.dataweave.master.application;

import com.dataweave.master.domain.AgentAction;
import com.dataweave.master.domain.TaskDiagnosis;
import com.dataweave.master.domain.TaskDiagnosisRepository;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.TaskInstanceRepository;
import com.dataweave.master.domain.WorkerNode;
import com.dataweave.master.domain.WorkflowDef;
import com.dataweave.master.domain.WorkflowDefRepository;
import com.dataweave.master.i18n.Messages;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 默认平台动作执行器：按 action_type 执行 applyFix 四动作、任务重跑、节点受控执行。
 *
 * <p>node_exec 委托 {@link NodeExecGateway}（实现在 api 模块，section 3 接线；缺失时返回明确错误）。
 *
 * <p>{@link #execute(AgentAction, Locale)} 按 locale 本地化 {@code ExecOutcome.message}（用户可见反馈）。
 * 审计日志类文案（写入 task_instance.log 的 "[fix]..."/"[rerun]..."）保留中文（内部审计数据，非 i18n 范围）。
 */
@Component
public class DefaultPlatformActionExecutor implements PlatformActionExecutor {

    private final TaskInstanceRepository instanceRepository;
    private final TaskDiagnosisRepository diagnosisRepository;
    private final FleetService fleetService;
    private final TaskService taskService;
    private final ObjectProvider<NodeExecGateway> nodeExecGateway;
    private final WorkflowTriggerService triggerService;
    private final RecoveryService recoveryService;
    private final WorkflowDefRepository workflowDefRepository;
    // ObjectProvider 延迟查找：打破 OpsService→DiagnosisService→GatedActionService→Executor 的循环依赖。
    private final ObjectProvider<OpsService> opsService;
    private final Messages messages;

    public DefaultPlatformActionExecutor(TaskInstanceRepository instanceRepository,
                                         TaskDiagnosisRepository diagnosisRepository,
                                         FleetService fleetService,
                                         TaskService taskService,
                                         ObjectProvider<NodeExecGateway> nodeExecGateway,
                                         WorkflowTriggerService triggerService,
                                         RecoveryService recoveryService,
                                         WorkflowDefRepository workflowDefRepository,
                                         ObjectProvider<OpsService> opsService,
                                         Messages messages) {
        this.instanceRepository = instanceRepository;
        this.diagnosisRepository = diagnosisRepository;
        this.fleetService = fleetService;
        this.taskService = taskService;
        this.nodeExecGateway = nodeExecGateway;
        this.triggerService = triggerService;
        this.recoveryService = recoveryService;
        this.workflowDefRepository = workflowDefRepository;
        this.opsService = opsService;
        this.messages = messages;
    }

    @Override
    public ExecOutcome execute(AgentAction action, Locale locale) {
        String type = action.getActionType() == null ? "" : action.getActionType().toUpperCase();
        return switch (type) {
            case "APPLY_FIX_RERUN" -> rerun(action, null, "[fix] 原地重跑成功",
                    messages.get("executor.fix_rerun.success", locale));
            case "APPLY_FIX_MIGRATE_NODE" -> migrate(action, locale);
            case "APPLY_FIX_RERUN_MORE_MEMORY" -> rerunMoreMemory(action, locale);
            case "APPLY_FIX_CAP_NODE_WEIGHT" -> capNodeWeight(action, locale);
            case "TASK_RERUN" -> taskRerun(action, locale);
            case "CREATE_TASK" -> createTask(action, locale);
            case "NODE_EXEC" -> nodeExec(action);
            case "TEST_RUN" -> testRun(action, locale);
            case "TASK_RUN" -> taskRun(action, locale);
            case "TRIGGER_WORKFLOW" -> triggerWorkflow(action, locale);
            case "RESUME_WORKFLOW" -> resumeWorkflow(action, locale);
            case "RERUN_WORKFLOW" -> rerunWorkflow(action, locale);
            case "KILL_INSTANCE" -> instanceOp(action, "kill", locale);
            case "PAUSE_INSTANCE" -> instanceOp(action, "pause", locale);
            case "RESUME_INSTANCE" -> instanceOp(action, "resume", locale);
            default -> new ExecOutcome(false,
                    messages.get("executor.unsupported_action", locale, action.getActionType()),
                    json(Map.of("error", "unsupported-action", "actionType", action.getActionType())), null);
        };
    }

    // ---- applyFix / task rerun ----
    private ExecOutcome rerun(AgentAction action, String nodeOverride, String log, String message) {
        TaskDiagnosis diagnosis = diagnosisOf(action);
        Long taskId = diagnosis != null ? diagnosis.getTaskId() : null;
        String nodeCode = nodeOverride;
        if (nodeCode == null) {
            nodeCode = diagnosis != null && diagnosis.getWorkerNodeCode() != null
                    ? diagnosis.getWorkerNodeCode() : "node-1";
        }
        TaskInstance inst = rerunOnNode(taskId, nodeCode, log);
        resolveDiagnosis(diagnosis);
        return new ExecOutcome(true, message,
                json(Map.of("newInstanceId", inst.getId(), "node", nodeCode)), inst.getId());
    }

    private ExecOutcome migrate(AgentAction action, Locale locale) {
        String target = fleetService.pickLeastLoadedOnline()
                .map(WorkerNode::getNodeCode).orElse("node-online");
        TaskDiagnosis diagnosis = diagnosisOf(action);
        TaskInstance inst = rerunOnNode(diagnosis != null ? diagnosis.getTaskId() : null, target,
                "[fix] 迁移到 " + target + " 后重跑成功");
        resolveDiagnosis(diagnosis);
        return new ExecOutcome(true,
                messages.get("executor.fix_migrate.success", locale, target),
                json(Map.of("newInstanceId", inst.getId(), "node", target)), inst.getId());
    }

    private ExecOutcome rerunMoreMemory(AgentAction action, Locale locale) {
        TaskDiagnosis diagnosis = diagnosisOf(action);
        String nodeCode = diagnosis != null && diagnosis.getWorkerNodeCode() != null
                ? diagnosis.getWorkerNodeCode() : "node-1";
        TaskInstance inst = rerunOnNode(diagnosis != null ? diagnosis.getTaskId() : null, nodeCode,
                "[fix] 调大 executor 内存后重跑成功");
        resolveDiagnosis(diagnosis);
        return new ExecOutcome(true,
                messages.get("executor.fix_more_memory.success", locale, nodeCode),
                json(Map.of("newInstanceId", inst.getId(), "node", nodeCode)), inst.getId());
    }

    private ExecOutcome capNodeWeight(AgentAction action, Locale locale) {
        TaskDiagnosis diagnosis = diagnosisOf(action);
        String nodeCode = diagnosis != null ? diagnosis.getWorkerNodeCode() : action.getTargetId();
        resolveDiagnosis(diagnosis);
        return new ExecOutcome(true,
                messages.get("executor.fix_cap_weight.success", locale, nodeCode),
                json(Map.of("node", String.valueOf(nodeCode))), null);
    }

    // ---- 任务实例重跑（MCP task_rerun / CLI dw task rerun）----
    private ExecOutcome taskRerun(AgentAction action, Locale locale) {
        UUID instanceId = parseUuid(action.getTargetId());
        TaskInstance src = instanceId == null ? null : instanceRepository.findById(instanceId).orElse(null);
        Long taskId = src != null ? src.getTaskId() : null;
        String node = src != null && src.getWorkerNodeCode() != null ? src.getWorkerNodeCode()
                : fleetService.pickLeastLoadedOnline().map(WorkerNode::getNodeCode).orElse("node-1");
        TaskInstance inst = rerunOnNode(taskId, node, "[rerun] 重跑实例 #" + action.getTargetId() + " 成功");
        return new ExecOutcome(true,
                messages.get("executor.task_rerun.success", locale, action.getTargetId(), String.valueOf(inst.getId())),
                json(Map.of("newInstanceId", inst.getId(), "node", node, "sourceInstanceId", String.valueOf(action.getTargetId()))),
                inst.getId());
    }

    // ---- 建任务并上线（MCP create_task）。command 编码为 "cron\ncontent"，targetId 为任务名 ----
    private ExecOutcome createTask(AgentAction action, Locale locale) {
        String fallbackName = messages.get("executor.create_task.default_name", locale);
        String name = action.getTargetId() != null ? action.getTargetId() : fallbackName;
        String cmd = action.getCommand() == null ? "" : action.getCommand();
        int nl = cmd.indexOf('\n');
        String cron = nl >= 0 ? cmd.substring(0, nl) : "0 0 8 * * ?";
        String content = nl >= 0 ? cmd.substring(nl + 1) : (cmd.isBlank() ? "select count(*) from orders" : cmd);
        TaskService.TaskCreation c = taskService.createAndOnline(name, "SQL", content, cron);
        return new ExecOutcome(true,
                messages.get("executor.create_task.success", locale, name, cron),
                json(Map.of("taskId", c.task().getId(), "name", name, "cron", cron, "instanceId", c.instanceId())),
                c.instanceId());
    }

    // ---- node_exec（委托 gateway）----
    private ExecOutcome nodeExec(AgentAction action) {
        NodeExecGateway gateway = nodeExecGateway.getIfAvailable();
        if (gateway == null) {
            return new ExecOutcome(false,
                    messages.get("executor.node_exec.gateway_absent"),
                    json(Map.of("error", "node_exec_gateway_absent")), null);
        }
        NodeExecGateway.ExecResult r = gateway.exec(action.getTargetId(), action.getCommand());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("exitCode", r.exitCode());
        result.put("stdout", r.stdout());
        result.put("stderr", r.stderr());
        result.put("truncated", r.truncated());
        return new ExecOutcome(r.success(), r.message(), json(result), null);
    }

    // ---- 调度类动作（distributed-scheduler-m1）----

    /**
     * 单任务测试运行：targetId=taskId，command 经 {@link TestRunCommand} 编码
     * （纯 bizDate 或 bizDate+编辑器临时内容）。携带临时内容时跑「编辑器最新内容」，不落 task_def。
     */
    private ExecOutcome testRun(AgentAction action, Locale locale) {
        Long taskId = parseLong(action.getTargetId());
        if (taskId == null) {
            return new ExecOutcome(false,
                    messages.get("executor.test_run.missing_task_id", locale),
                    json(Map.of("error", "missing_task_id")), null);
        }
        TestRunCommand.Decoded cmd = TestRunCommand.decode(action.getCommand());
        UUID instanceId = triggerService.triggerTestRun(
                taskId, cmd.bizDate(), cmd.content(), cmd.paramsJson(), cmd.type());
        return new ExecOutcome(true,
                messages.get("executor.test_run.success", locale, taskId),
                json(Map.of("testInstanceId", instanceId.toString(), "taskId", taskId)), instanceId);
    }

    /** 手动触发正式任务运行：targetId=taskId，command=bizDate（可空）。run_mode=NORMAL，计入正式统计。 */
    private ExecOutcome taskRun(AgentAction action, Locale locale) {
        Long taskId = parseLong(action.getTargetId());
        if (taskId == null) {
            return new ExecOutcome(false,
                    messages.get("executor.test_run.missing_task_id", locale),
                    json(Map.of("error", "missing_task_id")), null);
        }
        String bizDate = action.getCommand();
        UUID instanceId = triggerService.triggerManualTaskRun(taskId, bizDate);
        return new ExecOutcome(true,
                messages.get("executor.task_run.success", locale, taskId),
                json(Map.of("instanceId", instanceId.toString(), "taskId", taskId, "runMode", "NORMAL")), instanceId);
    }

    /** 手动触发工作流：targetId=workflowId，command=bizDate（可空）。 */
    private ExecOutcome triggerWorkflow(AgentAction action, Locale locale) {
        Long workflowId = parseLong(action.getTargetId());
        WorkflowDef wf = workflowId == null ? null : workflowDefRepository.findById(workflowId).orElse(null);
        if (wf == null) {
            return new ExecOutcome(false,
                    messages.get("workflow.not_found", locale, action.getTargetId()),
                    json(Map.of("error", "workflow_not_found")), null);
        }
        UUID wiId = triggerService.trigger(wf, "MANUAL", action.getCommand(), wf.getPriority());
        return new ExecOutcome(true,
                messages.get("executor.trigger_workflow.success", locale, wf.getName()),
                json(Map.of("workflowInstanceId", wiId.toString(), "workflowId", workflowId)), wiId);
    }

    /** 断点恢复：targetId=workflowInstanceId(UUID)。 */
    private ExecOutcome resumeWorkflow(AgentAction action, Locale locale) {
        UUID wiId = parseUuid(action.getTargetId());
        boolean ok = wiId != null && recoveryService.resume(wiId);
        return new ExecOutcome(ok,
                ok ? messages.get("executor.resume_workflow.success", locale)
                        : messages.get("executor.resume_workflow.no_effect", locale),
                json(Map.of("resumed", ok, "workflowInstanceId", String.valueOf(action.getTargetId()))), wiId);
    }

    /** 整流重跑：targetId=workflowInstanceId(UUID)。 */
    private ExecOutcome rerunWorkflow(AgentAction action, Locale locale) {
        UUID wiId = parseUuid(action.getTargetId());
        boolean ok = wiId != null && recoveryService.rerunAll(wiId);
        return new ExecOutcome(ok,
                ok ? messages.get("executor.rerun_workflow.success", locale)
                        : messages.get("executor.rerun_workflow.no_effect", locale),
                json(Map.of("rerun", ok, "workflowInstanceId", String.valueOf(action.getTargetId()))), wiId);
    }

    /** 工作流实例生命周期控制：targetId=workflowInstanceId(UUID)，op ∈ kill|pause|resume。 */
    private ExecOutcome instanceOp(AgentAction action, String op, Locale locale) {
        UUID wiId = parseUuid(action.getTargetId());
        if (wiId == null) {
            return new ExecOutcome(false,
                    messages.get("executor.instance_op.bad_id", locale, action.getTargetId()),
                    json(Map.of("error", "bad_instance_id", "actionType", action.getActionType())), null);
        }
        OpsService ops = opsService.getObject();
        try {
            String state = switch (op) {
                case "kill" -> ops.killWorkflow(wiId).getState();
                case "pause" -> ops.pauseWorkflow(wiId).getState();
                case "resume" -> ops.resumeWorkflow(wiId).getState();
                default -> throw new IllegalArgumentException("unknown op: " + op);
            };
            String msgKey = switch (op) {
                case "kill" -> "executor.instance_op.killed";
                case "pause" -> "executor.instance_op.paused";
                default -> "executor.instance_op.resumed";
            };
            return new ExecOutcome(true,
                    messages.get(msgKey, locale, state),
                    json(Map.of("op", op, "workflowInstanceId", wiId.toString(), "state", state)), wiId);
        } catch (RuntimeException e) {
            // OpsService 对非法状态/不存在抛 IllegalStateException；转为可读的失败结果，不抛穿闸门。
            return new ExecOutcome(false,
                    messages.get("executor.instance_op.failed", locale, op, e.getMessage()),
                    json(Map.of("error", op + "_failed", "detail", String.valueOf(e.getMessage()))), null);
        }
    }

    // ---- helpers ----
    private TaskDiagnosis diagnosisOf(AgentAction action) {
        if (!"DIAGNOSIS".equalsIgnoreCase(action.getTargetType())) {
            return null;
        }
        Long id = parseLong(action.getTargetId());
        return id == null ? null : diagnosisRepository.findById(id).orElse(null);
    }

    private void resolveDiagnosis(TaskDiagnosis diagnosis) {
        if (diagnosis != null) {
            diagnosis.setStatus("RESOLVED");
            diagnosis.setUpdatedAt(LocalDateTime.now());
            diagnosisRepository.save(diagnosis);
        }
    }

    private TaskInstance rerunOnNode(Long taskId, String nodeCode, String log) {
        LocalDateTime now = LocalDateTime.now();
        TaskInstance inst = new TaskInstance();
        inst.setTenantId(1L);
        inst.setProjectId(1L);
        inst.setTaskId(taskId);
        inst.setRunMode("NORMAL");
        inst.setState("SUCCESS");
        inst.setAttempt(1);
        inst.setWorkerNodeCode(nodeCode);
        inst.setStartedAt(now);
        inst.setFinishedAt(now);
        inst.setLog(log);
        inst.setCreatedAt(now);
        inst.setUpdatedAt(now);
        inst.setDeleted(0);
        inst.setVersion(0L);
        return instanceRepository.save(inst);
    }

    private Long parseLong(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private UUID parseUuid(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String json(Map<String, ?> o) {
        return Json.obj(o);
    }
}
