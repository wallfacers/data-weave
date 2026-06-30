package com.dataweave.master.application;

import com.dataweave.master.domain.AgentAction;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.TaskInstanceRepository;
import com.dataweave.master.domain.WorkerNode;
import com.dataweave.master.domain.WorkflowDef;
import com.dataweave.master.domain.WorkflowDefRepository;
import com.dataweave.master.i18n.BizException;
import com.dataweave.master.i18n.Messages;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.type.TypeReference;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
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
    // 023 资产目录 + 指标市场写执行（ObjectProvider 延迟查找，避免装配期循环依赖）。
    private final ObjectProvider<com.dataweave.master.application.asset.AssetCatalogService> assetCatalogService;
    private final ObjectProvider<com.dataweave.master.application.asset.MetricListingService> metricListingService;
    private final ObjectProvider<com.dataweave.master.application.asset.AssetSubscriptionService> assetSubscriptionService;
    private final Messages messages;
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
                                         ObjectProvider<com.dataweave.master.application.asset.AssetCatalogService> assetCatalogService,
                                         ObjectProvider<com.dataweave.master.application.asset.MetricListingService> metricListingService,
                                         ObjectProvider<com.dataweave.master.application.asset.AssetSubscriptionService> assetSubscriptionService,
                                         Messages messages) {
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
        this.assetCatalogService = assetCatalogService;
        this.metricListingService = metricListingService;
        this.assetSubscriptionService = assetSubscriptionService;
        this.messages = messages;
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
            // 023 资产目录 + 指标市场写执行（command=JSON payload，含 op 分流）
            case "ASSET_WRITE" -> assetWrite(action, locale);
            case "METRIC_CERTIFY" -> metricCertify(action, locale);
            case "ASSET_SUBSCRIBE" -> assetSubscribe(action, locale);
            default -> new ExecOutcome(false,
                    messages.get("executor.unsupported_action", locale, action.getActionType()),
                    json(Map.of("error", "unsupported-action", "actionType", action.getActionType())), null);
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

    // ═══ 023 资产目录 + 指标市场写执行（command=JSON：tenantId/projectId/userId + op + 载荷）═══

    /** 资产编目/上架/复用写：op ∈ asset.create|asset.update|asset.retire|asset.reconcile|metric.list|metric.delist|metric.reuse。 */
    @SuppressWarnings("unchecked")
    private ExecOutcome assetWrite(AgentAction action, Locale locale) {
        Map<String, Object> p = parsePayload(action);
        if (p == null) {
            return new ExecOutcome(false, messages.get("executor.asset.missing_payload", locale),
                    json(Map.of("error", "missing_payload")), null);
        }
        Long tenantId = longVal(p, "tenantId");
        Long projectId = longVal(p, "projectId");
        Long userId = longVal(p, "userId");
        String op = strVal(p, "op");
        if (tenantId == null || projectId == null || userId == null || op == null) {
            return new ExecOutcome(false, messages.get("executor.asset.missing_params", locale),
                    json(Map.of("error", "missing_tenant_project_user_or_op")), null);
        }
        // 业务校验错误（BizException：duplicate/reuse_cycle/not_found…）有意透传 → GlobalExceptionHandler 出稳定错误码。
        Map<String, Object> out = new LinkedHashMap<>();
        switch (op) {
            case "asset.create" -> {
                var a = assetCatalogService.getObject().create(tenantId, projectId, userId,
                        (Map<String, Object>) p.getOrDefault("asset", Map.of()));
                out.put("assetId", a.getId());
            }
            case "asset.update" -> {
                Long id = longVal(p, "id");
                var a = assetCatalogService.getObject().update(tenantId, userId, id,
                        (Map<String, Object>) p.getOrDefault("patch", Map.of()));
                out.put("assetId", a.getId());
            }
            case "asset.retire" -> {
                Long id = longVal(p, "id");
                var a = assetCatalogService.getObject().retire(tenantId, userId, id);
                out.put("assetId", a.getId());
                out.put("status", a.getStatus());
            }
            case "asset.reconcile" -> {
                Long id = longVal(p, "id");
                var a = assetCatalogService.getObject().reconcile(tenantId, userId, id);
                out.put("assetId", a.getId());
                out.put("status", a.getStatus());
            }
            case "metric.list" -> {
                var m = metricListingService.getObject().list(tenantId, projectId, userId,
                        (Map<String, Object>) p.getOrDefault("metric", Map.of()));
                out.put("listingId", m.getId());
            }
            case "metric.delist" -> {
                Long id = longVal(p, "id");
                var m = metricListingService.getObject().delist(tenantId, userId, id);
                out.put("listingId", m.getId());
                out.put("status", m.getStatus());
            }
            case "metric.reuse" -> {
                Long id = longVal(p, "id");
                var ref = metricListingService.getObject().reuse(tenantId, projectId, userId, id,
                        strVal(p, "consumerType"), strVal(p, "consumerRef"));
                out.put("reuseId", ref.getId());
            }
            default -> {
                return new ExecOutcome(false, messages.get("executor.asset.unknown_op", locale, op),
                        json(Map.of("error", "unknown_op", "op", op)), null);
            }
        }
        return new ExecOutcome(true, messages.get("executor.asset.success", locale, op), json(out), null);
    }

    /** 指标认证（L2）：targetId=listingId 或 payload.id。 */
    private ExecOutcome metricCertify(AgentAction action, Locale locale) {
        Map<String, Object> p = parsePayload(action);
        Long tenantId = p != null ? longVal(p, "tenantId") : null;
        Long userId = p != null ? longVal(p, "userId") : null;
        Long id = p != null ? longVal(p, "id") : null;
        if (id == null) id = parseLong(action.getTargetId());
        if (tenantId == null || userId == null || id == null) {
            return new ExecOutcome(false, messages.get("executor.asset.missing_params", locale),
                    json(Map.of("error", "missing_tenant_user_or_id")), null);
        }
        var m = metricListingService.getObject().certify(tenantId, userId, id); // BizException 透传
        return new ExecOutcome(true, messages.get("executor.metric.certified", locale, id),
                json(Map.of("listingId", m.getId(), "certification", m.getCertification())), null);
    }

    /** 订阅/退订：op ∈ subscribe|unsubscribe。 */
    private ExecOutcome assetSubscribe(AgentAction action, Locale locale) {
        Map<String, Object> p = parsePayload(action);
        if (p == null) {
            return new ExecOutcome(false, messages.get("executor.asset.missing_payload", locale),
                    json(Map.of("error", "missing_payload")), null);
        }
        Long tenantId = longVal(p, "tenantId");
        Long userId = longVal(p, "userId");
        String op = strVal(p, "op");
        if (tenantId == null || userId == null || op == null) {
            return new ExecOutcome(false, messages.get("executor.asset.missing_params", locale),
                    json(Map.of("error", "missing_tenant_user_or_op")), null);
        }
        if ("unsubscribe".equals(op)) {
            assetSubscriptionService.getObject().unsubscribe(tenantId, userId, longVal(p, "id"));
            return new ExecOutcome(true, messages.get("executor.asset.unsubscribed", locale),
                    json(Map.of("unsubscribed", true)), null);
        }
        var sub = assetSubscriptionService.getObject().subscribe(tenantId, userId,
                strVal(p, "targetType"), longVal(p, "targetId"), strVal(p, "changeFilter"));
        return new ExecOutcome(true, messages.get("executor.asset.subscribed", locale),
                json(Map.of("subscriptionId", sub.getId())), null);
    }

    private Map<String, Object> parsePayload(AgentAction action) {
        String cmd = action.getCommand();
        if (cmd == null || cmd.isBlank()) return null;
        try {
            return objectMapper.readValue(cmd, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    private String strVal(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : String.valueOf(v);
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
