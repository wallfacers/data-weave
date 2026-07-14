package com.dataweave.master.application;

import com.dataweave.master.application.incident.IncidentEventPublisher;
import com.dataweave.master.domain.AgentAction;
import com.dataweave.master.domain.TaskDef;
import com.dataweave.master.domain.TaskDefRepository;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.TaskInstanceRepository;
import com.dataweave.master.domain.WorkerNode;
import com.dataweave.master.domain.WorkflowDef;
import com.dataweave.master.domain.WorkflowDefRepository;
import com.dataweave.master.domain.incident.Incident;
import com.dataweave.master.domain.incident.IncidentEvent;
import com.dataweave.master.domain.incident.IncidentProposal;
import com.dataweave.master.domain.incident.IncidentStates;
import com.dataweave.master.domain.incident.MessageKinds;
import com.dataweave.master.domain.incident.ProposalStatuses;
import com.dataweave.master.i18n.BizException;
import com.dataweave.master.i18n.Messages;
import com.dataweave.master.infrastructure.CheckpointRepository;
import com.dataweave.master.infrastructure.incident.IncidentMessageRepository;
import com.dataweave.master.infrastructure.incident.IncidentProposalRepository;
import com.dataweave.master.infrastructure.incident.IncidentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.type.TypeReference;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 默认平台动作执行器：按 action_type 执行任务/工作流运行与重跑、回滚、实例生命周期、节点受控执行等。
 *
 * <p>node_exec 委托 {@link NodeExecGateway}（实现在 api 模块，section 3 接线；缺失时返回明确错误）。
 *
 * <p>{@link #execute(AgentAction, Locale)} 按 locale 本地化 {@code ExecOutcome.message}（用户可见反馈）。
 * 审计日志类文案（写入 task_instance.log 的 "[fix]..."/"[rerun]..."）保留中文（内部审计数据，非 i18n 范围）。
 */
@Component
public class DefaultPlatformActionExecutor implements PlatformActionExecutor {

    private static final Logger log = LoggerFactory.getLogger(DefaultPlatformActionExecutor.class);

    private final TaskInstanceRepository instanceRepository;
    private final FleetService fleetService;
    private final TaskService taskService;
    private final WorkflowService workflowService;
    private final ObjectProvider<NodeExecGateway> nodeExecGateway;
    private final WorkflowTriggerService triggerService;
    private final RecoveryService recoveryService;
    private final WorkflowDefRepository workflowDefRepository;
    // ObjectProvider 延迟查找：打破 OpsService→GatedActionService→Executor 的循环依赖。
    private final ObjectProvider<OpsService> opsService;
    // ObjectProvider 延迟查找：打破 ProjectSyncService→TaskService→Executor 的循环依赖（E 子特性 project_push 执行接线）。
    private final ObjectProvider<ProjectSyncService> projectSyncService;
    // SPI：业务模块（alert 等）注入的 handler，兜底遍历委派（master 编译期只依赖接口，不反向依赖业务模块）。
    private final List<PlatformActionHandler> handlers;
    private final ObjectProvider<com.dataweave.master.application.lineage.LineageCorrectionService> lineageCorrectionService;
    private final ObjectProvider<BackfillService> backfillService;
    private final Messages messages;
    private final TaskDefRepository taskDefRepository;
    private final CheckpointRepository checkpointRepository;
    private final IncidentRepository incidentRepository;
    private final IncidentProposalRepository incidentProposalRepository;
    private final IncidentMessageRepository incidentMessageRepository;
    private final IncidentEventPublisher incidentEventPublisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DefaultPlatformActionExecutor(TaskInstanceRepository instanceRepository,
                                         FleetService fleetService,
                                         TaskService taskService,
                                         WorkflowService workflowService,
                                         ObjectProvider<NodeExecGateway> nodeExecGateway,
                                         WorkflowTriggerService triggerService,
                                         RecoveryService recoveryService,
                                         WorkflowDefRepository workflowDefRepository,
                                         ObjectProvider<OpsService> opsService,
                                         ObjectProvider<ProjectSyncService> projectSyncService,
                                         List<PlatformActionHandler> handlers,
                                         ObjectProvider<com.dataweave.master.application.lineage.LineageCorrectionService> lineageCorrectionService,
                                         ObjectProvider<BackfillService> backfillService,
                                         Messages messages,
                                         TaskDefRepository taskDefRepository,
                                         CheckpointRepository checkpointRepository,
                                         IncidentRepository incidentRepository,
                                         IncidentProposalRepository incidentProposalRepository,
                                         IncidentMessageRepository incidentMessageRepository,
                                         IncidentEventPublisher incidentEventPublisher) {
        this.instanceRepository = instanceRepository;
        this.fleetService = fleetService;
        this.taskService = taskService;
        this.workflowService = workflowService;
        this.nodeExecGateway = nodeExecGateway;
        this.triggerService = triggerService;
        this.recoveryService = recoveryService;
        this.workflowDefRepository = workflowDefRepository;
        this.opsService = opsService;
        this.projectSyncService = projectSyncService;
        this.handlers = handlers != null ? handlers : List.of();
        this.lineageCorrectionService = lineageCorrectionService;
        this.backfillService = backfillService;
        this.messages = messages;
        this.taskDefRepository = taskDefRepository;
        this.checkpointRepository = checkpointRepository;
        this.incidentRepository = incidentRepository;
        this.incidentProposalRepository = incidentProposalRepository;
        this.incidentMessageRepository = incidentMessageRepository;
        this.incidentEventPublisher = incidentEventPublisher;
    }

    @Override
    public ExecOutcome execute(AgentAction action, Locale locale) {
        String type = action.getActionType() == null ? "" : action.getActionType().toUpperCase();
        return switch (type) {
            case "TASK_RERUN" -> taskRerun(action, locale);
            case "NODE_EXEC" -> nodeExec(action);
            case "TEST_RUN" -> testRun(action, locale);
            case "TASK_RUN" -> taskRun(action, locale);
            case "TRIGGER_WORKFLOW" -> triggerWorkflow(action, locale);
            case "RESUME_WORKFLOW" -> resumeWorkflow(action, locale);
            case "RERUN_WORKFLOW" -> rerunWorkflow(action, locale);
            case "KILL_INSTANCE" -> instanceOp(action, "kill", locale);
            case "PAUSE_INSTANCE" -> instanceOp(action, "pause", locale);
            case "RESUME_INSTANCE" -> instanceOp(action, "resume", locale);
            // 运维中心批量操作（data-ops-center）：targetType=TASK_INSTANCE，对单个 task_instance 生效。
            case "OPS_SET_SUCCESS" -> opsTaskInstanceOp(action, "set-success", locale);
            case "OPS_RERUN_INSTANCE" -> opsTaskInstanceOp(action, "rerun", locale);
            case "OPS_KILL_INSTANCE" -> opsTaskInstanceOp(action, "kill", locale);
            case "ROLLBACK_TASK" -> rollbackTask(action, locale);
            case "ROLLBACK_WORKFLOW" -> rollbackWorkflow(action, locale);
            // E 子特性：project_push 执行接线（E4）
            case "PROJECT_PUSH", "PROJECT_PUSH_DESTRUCTIVE" -> projectPush(action, locale);
            // 041 血缘人工修正（镜像 projectPush：command=JSON payload → 领域服务）
            case "LINEAGE_EDGE_CONFIRM" -> lineageCorrection(action, "CONFIRM", locale);
            case "LINEAGE_EDGE_REMOVE" -> lineageCorrection(action, "REMOVE", locale);
            case "LINEAGE_CORRECTION_REVOKE" -> lineageCorrection(action, "REVOKE", locale);
            case "BACKFILL" -> backfill(action, locale);
            // 069 运维 Agent 自愈动作：targetType=TASK_INSTANCE（rerun/resume-checkpoint/reverify）
            // 或 TASK（adjust-resources 改 task_def）；command 携带结构化参数（同 backfill/lineage 惯例）。
            case "INCIDENT_RERUN", "INCIDENT_REVERIFY" -> incidentRerun(action, locale);
            case "INCIDENT_ADJUST_RESOURCES" -> incidentAdjustResources(action, locale);
            case "INCIDENT_RESUME_CHECKPOINT" -> incidentResumeCheckpoint(action, locale);
            case "INCIDENT_PUBLISH_FIX" -> incidentPublishFix(action, locale);
            default -> {
                // 021-alert SPI 兜底委派：遍历业务模块注入的 handler（如 alert 的 AlertActionHandler）
                for (PlatformActionHandler h : handlers) {
                    if (h.supports(type)) {
                        yield h.handle(action, locale);
                    }
                }
                yield new ExecOutcome(false,
                        messages.get("executor.unsupported_action", locale, action.getActionType()),
                        json(Map.of("error", "unsupported-action", "actionType", action.getActionType())), null);
            }
        };
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
                taskId, cmd.bizDate(), cmd.content(), cmd.paramsJson(), cmd.type(), locale);
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
        UUID instanceId = triggerService.triggerManualTaskRun(taskId, bizDate, locale);
        return new ExecOutcome(true,
                messages.get("executor.task_run.success", locale, taskId),
                json(Map.of("instanceId", instanceId.toString(), "taskId", taskId, "runMode", "NORMAL")), instanceId);
    }

    /** 手动触发工作流：targetId=workflowId，command=TriggerCommand 编码的 bizDate(+scope+targetNodeKey)。 */
    private ExecOutcome triggerWorkflow(AgentAction action, Locale locale) {
        Long workflowId = parseLong(action.getTargetId());
        WorkflowDef wf = workflowId == null ? null : workflowDefRepository.findById(workflowId).orElse(null);
        if (wf == null) {
            return new ExecOutcome(false,
                    messages.get("workflow.not_found", locale, action.getTargetId()),
                    json(Map.of("error", "workflow_not_found")), null);
        }
        TriggerCommand.Decoded cmd = TriggerCommand.decode(action.getCommand());
        UUID wiId = triggerService.trigger(wf, "MANUAL", cmd.bizDate(), wf.getPriority(), locale,
                cmd.scope(), cmd.targetNodeKey());
        return new ExecOutcome(true,
                messages.get("executor.trigger_workflow.success", locale, wf.getName()),
                json(Map.of("workflowInstanceId", wiId.toString(), "workflowId", workflowId)), wiId);
    }

    /** 回滚任务到历史版本：targetId=taskId，command=versionNo。 */
    private ExecOutcome rollbackTask(AgentAction action, Locale locale) {
        Long taskId = parseLong(action.getTargetId());
        Integer versionNo = parseInt(action.getCommand());
        if (taskId == null || versionNo == null) {
            return new ExecOutcome(false,
                    messages.get("executor.rollback_task.missing_params", locale),
                    json(Map.of("error", "missing_task_id_or_version_no")), null);
        }
        try {
            taskService.rollback(taskId, versionNo);
            return new ExecOutcome(true,
                    messages.get("executor.rollback_task.success", locale, taskId, versionNo),
                    json(Map.of("taskId", taskId, "versionNo", versionNo)), null);
        } catch (Exception e) {
            return new ExecOutcome(false, e.getMessage(),
                    json(Map.of("error", e.getClass().getSimpleName())), null);
        }
    }

    /** 回滚工作流到历史版本：targetId=workflowId，command=versionNo。 */
    private ExecOutcome rollbackWorkflow(AgentAction action, Locale locale) {
        Long workflowId = parseLong(action.getTargetId());
        Integer versionNo = parseInt(action.getCommand());
        if (workflowId == null || versionNo == null) {
            return new ExecOutcome(false,
                    messages.get("executor.rollback_workflow.missing_params", locale),
                    json(Map.of("error", "missing_workflow_id_or_version_no")), null);
        }
        try {
            workflowService.rollback(workflowId, versionNo);
            return new ExecOutcome(true,
                    messages.get("executor.rollback_workflow.success", locale, workflowId, versionNo),
                    json(Map.of("workflowId", workflowId, "versionNo", versionNo)), null);
        } catch (Exception e) {
            return new ExecOutcome(false, e.getMessage(),
                    json(Map.of("error", e.getClass().getSimpleName())), null);
        }
    }

    /** 041 血缘人工修正：解码 command JSON → LineageCorrectionService.apply（PG 裁决 + 图即时同步）。 */
    private ExecOutcome lineageCorrection(AgentAction action, String correctionAction, Locale locale) {
        try {
            Map<String, Object> payload = objectMapper.readValue(action.getCommand(),
                    new TypeReference<Map<String, Object>>() {});
            Long tenantId = longVal(payload, "tenantId");
            Long projectId = longVal(payload, "projectId");
            Long taskDefId = longVal(payload, "taskDefId");
            String direction = (String) payload.get("direction");
            String tableKey = (String) payload.get("tableKey");
            String columnKey = (String) payload.get("columnKey");
            if (tenantId == null || projectId == null || taskDefId == null) {
                return new ExecOutcome(false,
                        messages.get("executor.lineage_correction.missing_params", locale),
                        json(Map.of("error", "missing_params")), null);
            }
            var view = lineageCorrectionService.getObject().apply(tenantId, projectId, taskDefId,
                    correctionAction, direction, tableKey, columnKey,
                    action.getActor() == null ? "unknown" : action.getActor());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("action", correctionAction);
            out.put("taskDefId", taskDefId);
            out.put("tableKey", tableKey);
            if (view != null) {
                out.put("correctionId", view.id());
                out.put("status", view.status());
            }
            return new ExecOutcome(true,
                    messages.get("executor.lineage_correction.success", locale, correctionAction),
                    json(out), null);
        } catch (BizException e) {
            return new ExecOutcome(false, e.getMessage(),
                    json(Map.of("error", e.getCode(), "detail", String.valueOf(e.getMessage()))), null);
        } catch (Exception e) {
            return new ExecOutcome(false,
                    messages.get("executor.lineage_correction.failed", locale, String.valueOf(e.getMessage())),
                    json(Map.of("error", "correction_failed", "detail", String.valueOf(e.getMessage()))), null);
        }
    }

    /** 补数据执行：从 command JSON 反序列化回填参数 → BackfillService.submitBackfill（L0/L1 同步 + L2+ 审批后统一路径）。 */
    private ExecOutcome backfill(AgentAction action, Locale locale) {
        String cmd = action.getCommand();
        if (cmd == null || cmd.isBlank()) {
            return new ExecOutcome(false,
                    messages.get("executor.backfill.missing_params", locale),
                    json(Map.of("error", "missing_backfill_params")), null);
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(cmd,
                    new TypeReference<Map<String, Object>>() {});
            String dateStart = (String) payload.get("dateStart");
            String dateEnd = (String) payload.get("dateEnd");
            boolean includeDownstream = Boolean.TRUE.equals(payload.get("includeDownstream"));
            int parallelism = payload.get("parallelism") instanceof Number n ? n.intValue() : 1;
            @SuppressWarnings("unchecked")
            List<Long> downstreamTaskIds = (List<Long>) payload.getOrDefault("downstreamTaskIds", List.of());

            OpsContracts.BackfillRequest req = new OpsContracts.BackfillRequest(
                    action.getTargetType(), Long.parseLong(action.getTargetId()),
                    dateStart, dateEnd, includeDownstream, parallelism, downstreamTaskIds);

            OpsContracts.BackfillRunView run = backfillService.getObject().submitBackfill(req);
            return new ExecOutcome(true,
                    messages.get("executor.backfill.success", locale, dateStart, dateEnd),
                    json(Map.of("backfillRunId", run.id().toString(),
                            "targetType", run.targetType(),
                            "targetName", run.targetName(),
                            "dateStart", run.dateStart(),
                            "dateEnd", run.dateEnd(),
                            "total", run.total())),
                    run.id());
        } catch (BizException e) {
            return new ExecOutcome(false, e.getMessage(),
                    json(Map.of("error", e.getCode(), "detail", String.valueOf(e.getMessage()))), null);
        } catch (Exception e) {
            log.error("[Backfill] executor failed", e);
            return new ExecOutcome(false,
                    messages.get("executor.backfill.failed", locale, String.valueOf(e.getMessage())),
                    json(Map.of("error", "backfill_failed", "detail", String.valueOf(e.getMessage()))), null);
        }
    }

    /** E 子特性 project_push 执行接线（E4）：解码 JSON payload → ProjectSyncService.push(@Transactional 全有或全无）。 */
    private ExecOutcome projectPush(AgentAction action, Locale locale) {
        String cmd = action.getCommand();
        if (cmd == null || cmd.isBlank()) {
            return new ExecOutcome(false,
                    messages.get("executor.project_push.missing_payload", locale),
                    json(Map.of("error", "missing_push_payload")), null);
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(cmd,
                    new TypeReference<Map<String, Object>>() {});
            Long projectId = longVal(payload, "projectId");
            Long tenantId = longVal(payload, "tenantId");
            Long userId = longVal(payload, "userId");
            @SuppressWarnings("unchecked")
            Map<String, String> files = (Map<String, String>) payload.getOrDefault("files", Map.of());
            String baseline = (String) payload.get("baseline");
            boolean force = Boolean.TRUE.equals(payload.get("force"));
            String remark = (String) payload.getOrDefault("remark",
                    "push via MCP (" + (action.getActionType() != null ? action.getActionType() : "PROJECT_PUSH") + ")");

            if (projectId == null || tenantId == null || userId == null) {
                return new ExecOutcome(false,
                        messages.get("executor.project_push.missing_params", locale),
                        json(Map.of("error", "missing_project_tenant_or_user")), null);
            }

            ProjectSyncDtos.PushCommand pushCmd = new ProjectSyncDtos.PushCommand(
                    files, baseline, force, null, remark);
            ProjectSyncService sync = projectSyncService.getObject();
            ProjectSyncDtos.PushResult result = sync.push(projectId, tenantId, userId, pushCmd);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("projectId", projectId);
            out.put("created", result.created());
            out.put("updated", result.updated());
            out.put("deleted", result.deleted());
            out.put("snapshots", result.snapshots());
            out.put("newBaseline", result.newBaseline());
            return new ExecOutcome(true,
                    messages.get("executor.project_push.success", locale, projectId),
                    json(out), null);
        } catch (BizException e) {
            return new ExecOutcome(false, e.getMessage(),
                    json(Map.of("error", e.getCode(), "detail", e.getMessage())), null);
        } catch (Exception e) {
            return new ExecOutcome(false,
                    messages.get("executor.project_push.failed", locale, e.getMessage()),
                    json(Map.of("error", "push_failed", "detail", String.valueOf(e.getMessage()))), null);
        }
    }

    private Long longVal(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Long.parseLong(s.trim()); } catch (NumberFormatException ignored) {}
        }
        return null;
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
                default -> throw new BizException("executor.unknown_op", op);
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

    /**
     * 运维中心单 task_instance 操作（data-ops-center 批量）：targetId=taskInstanceId(UUID)，
     * op ∈ set-success|rerun|kill。经 OpsService 领域方法（CAS/唤醒纪律在内）。非法状态/不存在转为可读失败结果，不抛穿闸门。
     */
    private ExecOutcome opsTaskInstanceOp(AgentAction action, String op, Locale locale) {
        UUID id = parseUuid(action.getTargetId());
        if (id == null) {
            return new ExecOutcome(false,
                    messages.get("executor.ops_task.bad_id", locale, action.getTargetId()),
                    json(Map.of("error", "bad_instance_id", "actionType", action.getActionType())), null);
        }
        OpsService ops = opsService.getObject();
        try {
            String state = switch (op) {
                case "set-success" -> ops.setSuccess(id).getState();
                case "rerun" -> ops.rerunInstance(id).getState();
                case "kill" -> ops.killTask(id).getState();
                default -> throw new BizException("executor.unknown_op", op);
            };
            String msgKey = switch (op) {
                case "set-success" -> "executor.ops_task.set_success";
                case "rerun" -> "executor.ops_task.rerun";
                default -> "executor.ops_task.killed";
            };
            return new ExecOutcome(true,
                    messages.get(msgKey, locale, state),
                    json(Map.of("op", op, "taskInstanceId", id.toString(), "state", String.valueOf(state))), id);
        } catch (RuntimeException e) {
            return new ExecOutcome(false,
                    messages.get("executor.ops_task.failed", locale, op, String.valueOf(e.getMessage())),
                    json(Map.of("error", op + "_failed", "detail", String.valueOf(e.getMessage()))), null);
        }
    }

    // ---- 069 运维 Agent 自愈动作 ----

    /** incident_rerun / incident_reverify：targetId=taskInstanceId，直接复用 OpsService.rerunInstance。 */
    private ExecOutcome incidentRerun(AgentAction action, Locale locale) {
        UUID id = parseUuid(action.getTargetId());
        if (id == null) {
            return new ExecOutcome(false,
                    messages.get("executor.ops_task.bad_id", locale, action.getTargetId()),
                    json(Map.of("error", "bad_instance_id", "actionType", action.getActionType())), null);
        }
        try {
            TaskInstance ti = opsService.getObject().rerunInstance(id);
            return new ExecOutcome(true,
                    messages.get("executor.ops_task.rerun", locale, ti.getState()),
                    json(Map.of("taskInstanceId", id.toString(), "state", String.valueOf(ti.getState()))), id);
        } catch (RuntimeException e) {
            return new ExecOutcome(false,
                    messages.get("executor.ops_task.failed", locale, "rerun", String.valueOf(e.getMessage())),
                    json(Map.of("error", "rerun_failed", "detail", String.valueOf(e.getMessage()))), null);
        }
    }

    /** incident_resume_checkpoint：targetId=taskInstanceId，command={"checkpointId":"..."}。 */
    @SuppressWarnings("unchecked")
    private ExecOutcome incidentResumeCheckpoint(AgentAction action, Locale locale) {
        UUID instanceId = parseUuid(action.getTargetId());
        if (instanceId == null) {
            return new ExecOutcome(false,
                    messages.get("executor.ops_task.bad_id", locale, action.getTargetId()),
                    json(Map.of("error", "bad_instance_id", "actionType", action.getActionType())), null);
        }
        UUID checkpointId;
        try {
            Map<String, Object> payload = objectMapper.readValue(action.getCommand(), new TypeReference<Map<String, Object>>() {});
            checkpointId = parseUuid(String.valueOf(payload.get("checkpointId")));
        } catch (Exception e) {
            return new ExecOutcome(false,
                    messages.get("executor.incident.bad_command", locale, String.valueOf(e.getMessage())),
                    json(Map.of("error", "bad_command", "detail", String.valueOf(e.getMessage()))), null);
        }
        if (checkpointId == null) {
            return new ExecOutcome(false,
                    messages.get("executor.incident.bad_command", locale, "checkpointId"),
                    json(Map.of("error", "missing_checkpoint_id")), null);
        }
        try {
            TaskInstance ti = opsService.getObject().resumeFromCheckpoint(instanceId, checkpointId);
            return new ExecOutcome(true,
                    messages.get("executor.ops_task.rerun", locale, ti.getState()),
                    json(Map.of("taskInstanceId", instanceId.toString(), "checkpointId", checkpointId.toString(),
                            "state", String.valueOf(ti.getState()))), instanceId);
        } catch (RuntimeException e) {
            return new ExecOutcome(false,
                    messages.get("executor.ops_task.failed", locale, "resume_from_checkpoint", String.valueOf(e.getMessage())),
                    json(Map.of("error", "resume_from_checkpoint_failed", "detail", String.valueOf(e.getMessage()))), null);
        }
    }

    /**
     * incident_adjust_resources：targetId=taskDefId，command={"instanceId","memoryMb","cpuCores"}。
     * 改 task_def.resources_json + 落新版本快照（不上线，snapshot-only）+ 重跑最新失败实例（护栏已在
     * RemediationPlanner 校验过，本层只负责执行）。
     */
    @SuppressWarnings("unchecked")
    private ExecOutcome incidentAdjustResources(AgentAction action, Locale locale) {
        Long taskId = parseLongOrNull(action.getTargetId());
        if (taskId == null) {
            return new ExecOutcome(false,
                    messages.get("executor.incident.bad_command", locale, "taskId"),
                    json(Map.of("error", "bad_task_id", "actionType", action.getActionType())), null);
        }
        Map<String, Object> payload;
        try {
            payload = objectMapper.readValue(action.getCommand(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return new ExecOutcome(false,
                    messages.get("executor.incident.bad_command", locale, String.valueOf(e.getMessage())),
                    json(Map.of("error", "bad_command", "detail", String.valueOf(e.getMessage()))), null);
        }
        UUID instanceId = parseUuid(String.valueOf(payload.get("instanceId")));
        Object memoryMbObj = payload.get("memoryMb");
        Object cpuCoresObj = payload.get("cpuCores");
        if (instanceId == null || !(memoryMbObj instanceof Number) || !(cpuCoresObj instanceof Number)) {
            return new ExecOutcome(false,
                    messages.get("executor.incident.bad_command", locale, "instanceId/memoryMb/cpuCores"),
                    json(Map.of("error", "missing_params")), null);
        }
        TaskDef task = taskDefRepository.findById(taskId).orElse(null);
        if (task == null) {
            return new ExecOutcome(false,
                    messages.get("task.not_found", locale, taskId),
                    json(Map.of("error", "task_not_found", "taskId", taskId)), null);
        }
        String resourcesJson = json(Map.of(
                "memoryMb", ((Number) memoryMbObj).intValue(), "cpuCores", ((Number) cpuCoresObj).intValue()));
        task.setResourcesJson(resourcesJson);
        taskDefRepository.save(task);
        taskService.writeTaskVersionSnapshot(task, null, "069 运维 Agent 自动调资源（护栏内自愈）");
        try {
            TaskInstance ti = opsService.getObject().rerunInstance(instanceId);
            return new ExecOutcome(true,
                    messages.get("executor.ops_task.rerun", locale, ti.getState()),
                    json(Map.of("taskId", taskId, "taskInstanceId", instanceId.toString(),
                            "resourcesJson", resourcesJson, "state", String.valueOf(ti.getState()))), instanceId);
        } catch (RuntimeException e) {
            // 资源已调整落盘（不回滚——下次运行即生效），仅重跑本身失败需如实反馈
            return new ExecOutcome(false,
                    messages.get("executor.ops_task.failed", locale, "adjust_resources_rerun", String.valueOf(e.getMessage())),
                    json(Map.of("error", "rerun_after_adjust_failed", "detail", String.valueOf(e.getMessage()),
                            "resourcesJson", resourcesJson)), null);
        }
    }

    /**
     * incident_publish_fix（T023，L3 批准后执行）：targetId=proposalId，command={"incidentId":"..."}。
     * 基线陈旧校验（task_def.currentVersionNo != proposal.baseVersionNo → STALE+转人工）→ 落新版本快照 →
     * 重跑同一 latestInstanceId 验证（{@code OpsService.rerunInstance} 原地复用实例，无需回写事故 latest_instance_id）→
     * 事故 AWAITING_APPROVAL→ACTING（交回 {@code IncidentAgentService.actOrVerify} 的 PUBLISHED 提案验证分支收尾）。
     */
    @SuppressWarnings("unchecked")
    private ExecOutcome incidentPublishFix(AgentAction action, Locale locale) {
        UUID proposalId = parseUuid(action.getTargetId());
        UUID incidentId;
        try {
            Map<String, Object> payload = objectMapper.readValue(action.getCommand(), new TypeReference<Map<String, Object>>() {});
            incidentId = parseUuid(String.valueOf(payload.get("incidentId")));
        } catch (Exception e) {
            return new ExecOutcome(false,
                    messages.get("executor.incident.bad_command", locale, String.valueOf(e.getMessage())),
                    json(Map.of("error", "bad_command", "detail", String.valueOf(e.getMessage()))), null);
        }
        IncidentProposal proposal = proposalId == null ? null : incidentProposalRepository.findById(proposalId).orElse(null);
        if (proposal == null || incidentId == null) {
            return new ExecOutcome(false,
                    messages.get("executor.incident.bad_command", locale, "proposalId/incidentId"),
                    json(Map.of("error", "proposal_or_incident_not_found")), null);
        }
        TaskDef task = taskDefRepository.findById(proposal.taskDefId()).orElse(null);
        if (task == null) {
            incidentRepository.casState(incidentId, IncidentStates.AWAITING_APPROVAL, IncidentStates.NEEDS_HUMAN);
            appendIncidentMessage(incidentId, MessageKinds.SYSTEM, "任务定义已被删除，修复提案无法发布，转人工介入", null, "system");
            return new ExecOutcome(false, messages.get("task.not_found", locale, proposal.taskDefId()),
                    json(Map.of("error", "task_not_found")), null);
        }
        Integer currentVersion = task.getCurrentVersionNo();
        if (currentVersion == null || currentVersion.intValue() != proposal.baseVersionNo()) {
            incidentProposalRepository.casStatus(proposalId, ProposalStatuses.PENDING, ProposalStatuses.STALE);
            incidentRepository.casState(incidentId, IncidentStates.AWAITING_APPROVAL, IncidentStates.NEEDS_HUMAN);
            appendIncidentMessage(incidentId, MessageKinds.SYSTEM, "修复提案基线已过期（任务已被其它变更修改），转人工介入", null, "system");
            return new ExecOutcome(false, messages.get("incident.proposal_stale", locale),
                    json(Map.of("error", "proposal_stale")), null);
        }

        task.setContent(proposal.proposedContent());
        taskDefRepository.save(task);
        int newVersion = taskService.writeTaskVersionSnapshot(task, null,
                "069 修复提案发布（事故 " + incidentId + "，提案 " + proposalId + "）");
        incidentProposalRepository.markPublished(proposalId, newVersion);

        Incident inc = incidentRepository.findById(incidentId).orElse(null);
        boolean cas = inc != null && incidentRepository.casState(incidentId, IncidentStates.AWAITING_APPROVAL, IncidentStates.ACTING);
        UUID resultInstanceId = null;
        if (cas) {
            try {
                TaskInstance ti = opsService.getObject().rerunInstance(inc.latestInstanceId());
                resultInstanceId = ti.getId();
            } catch (RuntimeException e) {
                log.warn("[incidentPublishFix] rerun after publish failed incidentId={}: {}", incidentId, e.toString());
            }
        }
        appendIncidentMessage(incidentId, MessageKinds.ACTION,
                "修复提案已发布（新版本 v" + newVersion + "），已重跑验证",
                json(Map.of("proposalId", proposalId.toString(), "publishedVersionNo", newVersion)), "ops-agent");
        return new ExecOutcome(true, messages.get("executor.incident.publish_fix.success", locale, newVersion),
                json(Map.of("proposalId", proposalId.toString(), "taskId", task.getId(), "publishedVersionNo", newVersion)),
                resultInstanceId);
    }

    /** 069 事故消息追加 + 直播广播（镜像 IncidentAgentService 同名逻辑，执行器侧独立小闭环，避免反向依赖 application.incident）。 */
    private void appendIncidentMessage(UUID incidentId, String kind, String content, String payloadJson, String actor) {
        var msg = incidentMessageRepository.append(incidentId, kind, content, payloadJson, actor);
        incidentRepository.findById(incidentId).ifPresent(inc -> {
            incidentEventPublisher.publish(inc.projectId(), new IncidentEvent.MessageAppended(incidentId, msg));
            incidentEventPublisher.publish(inc.projectId(), new IncidentEvent.IncidentChanged(inc));
        });
    }

    // ---- helpers ----
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

    private Integer parseInt(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Integer.parseInt(s.trim());
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

    private Long parseLongOrNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String json(Map<String, ?> o) {
        return Json.obj(o);
    }
}
