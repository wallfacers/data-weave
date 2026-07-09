package com.dataweave.master.application;

import com.dataweave.master.application.OpsContracts.InstanceQuery;
import com.dataweave.master.application.OpsContracts.InstanceRow;
import com.dataweave.master.application.OpsContracts.PageResult;
import com.dataweave.master.domain.AgentAction;
import com.dataweave.master.domain.AgentActionRepository;
import com.dataweave.master.domain.EventBus;
import com.dataweave.master.domain.InstanceStates;
import com.dataweave.master.domain.LogBus;
import com.dataweave.master.domain.TaskDef;
import com.dataweave.master.domain.TaskDefRepository;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.TaskInstanceRepository;
import com.dataweave.master.domain.WorkflowInstance;
import com.dataweave.master.domain.WorkflowInstanceRepository;
import com.dataweave.master.i18n.BizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * 调度运维 / 驾驶舱查询服务：任务定义、运行实例、全局运行概况。
 */
@Service
public class OpsService {

    private static final System.Logger log = System.getLogger(OpsService.class.getName());

    private final TaskDefRepository taskDefRepository;
    private final TaskInstanceRepository instanceRepository;
    private final WorkflowInstanceRepository workflowInstanceRepository;
    private final com.dataweave.master.domain.WorkflowDefRepository workflowDefRepository;
    private final InstanceStateMachine stateMachine;
    private final WorkflowStateService workflowStateService;
    private final LogBus logBus;
    private final EventBus eventBus;
    private final JdbcTemplate jdbc;
    private final AgentActionRepository agentActionRepository;

    public OpsService(TaskDefRepository taskDefRepository,
                      TaskInstanceRepository instanceRepository,
                      WorkflowInstanceRepository workflowInstanceRepository,
                      com.dataweave.master.domain.WorkflowDefRepository workflowDefRepository,
                      InstanceStateMachine stateMachine,
                      WorkflowStateService workflowStateService,
                      LogBus logBus,
                      EventBus eventBus,
                      JdbcTemplate jdbc,
                      AgentActionRepository agentActionRepository) {
        this.taskDefRepository = taskDefRepository;
        this.instanceRepository = instanceRepository;
        this.workflowInstanceRepository = workflowInstanceRepository;
        this.workflowDefRepository = workflowDefRepository;
        this.stateMachine = stateMachine;
        this.workflowStateService = workflowStateService;
        this.logBus = logBus;
        this.eventBus = eventBus;
        this.jdbc = jdbc;
        this.agentActionRepository = agentActionRepository;
    }

    /** 记录运维直接操作审计日志。绕过闸门的直接操作仍需留痕（FR-012）。 */
    private void audit(String actionType, UUID targetId, String summary) {
        AgentAction aa = new AgentAction();
        aa.setActionType(actionType);
        aa.setTargetType("TASK_INSTANCE");
        aa.setTargetId(targetId.toString());
        aa.setSummary(summary);
        aa.setActor("ops");
        aa.setActorSource("UI");
        aa.setPolicyLevel("L0");
        aa.setApprovalStatus("NONE");
        aa.setCreatedAt(LocalDateTime.now());
        aa.setExecutedAt(LocalDateTime.now());
        agentActionRepository.save(aa);
    }

    /** 非成功态：允许 set-success 的起始态（无运行事实的 NOT_RUN/WAITING/PAUSED 不在内）。 */
    private static final Set<String> SET_SUCCESS_FROM =
            Set.of(InstanceStates.FAILED, InstanceStates.STOPPED, InstanceStates.RUNNING, InstanceStates.PREEMPTED);

    /** 手动停止时向实例日志流插入的横幅（以 === 开头，前端 LogTab 会弱化着色，与执行 banner 一致）。 */
    private void appendManualStopLog(UUID taskInstanceId) {
        logBus.append(taskInstanceId, "=========== 手动停止 ===========");
        logBus.append(taskInstanceId, "状态: 已停止 | 操作: 用户手动停止运行");
    }


    /** 周期任务流列表（运维主体）：仅 status=ONLINE 且 schedule_type=CRON 的已发布工作流，按 id 升序。 */
    public List<com.dataweave.master.domain.WorkflowDef> periodicWorkflows(Long projectId) {
        return onlineWorkflowsByScheduleType("CRON", projectId);
    }

    /** 手动任务流列表（运维主体）：仅 status=ONLINE 且 schedule_type=MANUAL 的已发布工作流，按 id 升序。 */
    public List<com.dataweave.master.domain.WorkflowDef> manualWorkflows(Long projectId) {
        return onlineWorkflowsByScheduleType("MANUAL", projectId);
    }

    /** 036 向后兼容：无参版本保留供调度内核使用（不接项目上下文）。 */
    public List<com.dataweave.master.domain.WorkflowDef> periodicWorkflows() {
        return onlineWorkflowsByScheduleType("CRON", null);
    }

    /** 036 向后兼容：无参版本保留供调度内核使用。 */
    public List<com.dataweave.master.domain.WorkflowDef> manualWorkflows() {
        return onlineWorkflowsByScheduleType("MANUAL", null);
    }

    private List<com.dataweave.master.domain.WorkflowDef> onlineWorkflowsByScheduleType(String scheduleType, Long projectId) {
        List<com.dataweave.master.domain.WorkflowDef> list;
        if (projectId != null) {
            list = workflowDefRepository.findByScheduleTypeAndStatusAndDeleted(scheduleType, "ONLINE", 0).stream()
                    .filter(wf -> projectId.equals(wf.getProjectId()))
                    .collect(Collectors.toList());
        } else {
            list = workflowDefRepository.findByScheduleTypeAndStatusAndDeleted(scheduleType, "ONLINE", 0);
        }
        list.sort(Comparator.comparing(com.dataweave.master.domain.WorkflowDef::getId,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return list;
    }

    /**
     * 任务流列表多维筛选 + 分页（运维主体）：仅 status=ONLINE 且指定 schedule_type 的已发布工作流。
     * 名称模糊 / hasDraftChange / 最近触发结果(关联最近实例 state) / 目录 / 创建人，任一为空即不约束。
     * 最近触发结果用相关子查询求最近 workflow_instance.state（UUIDv7 时间有序，按 id 降序取首）。
     */
    public OpsContracts.PageResult<OpsContracts.WorkflowListRow> queryWorkflows(OpsContracts.WorkflowQuery q) {
        int page = Math.max(0, q.page());
        int size = Math.min(Math.max(1, q.size()), 200);
        // 最近触发结果子查询（H2/PG 通用：ORDER BY id DESC LIMIT 1）
        String recentSub = "(SELECT wi.state FROM workflow_instance wi WHERE wi.workflow_id=wd.id "
                + "ORDER BY wi.id DESC LIMIT 1)";
        StringBuilder where = new StringBuilder(
                " WHERE wd.deleted=0 AND wd.status='ONLINE' AND wd.schedule_type=? ");
        List<Object> args = new ArrayList<>();
        args.add(q.scheduleType());
        if (q.projectId() != null) {
            where.append("AND wd.project_id=? ");
            args.add(q.projectId());
        }
        if (q.keyword() != null && !q.keyword().isBlank()) {
            where.append("AND wd.name LIKE CONCAT('%', ?, '%') ");
            args.add(q.keyword().trim());
        }
        if (q.hasDraftChange() != null) {
            where.append("AND wd.has_draft_change=? ");
            args.add(q.hasDraftChange());
        }
        if (q.catalogNodeId() != null) {
            where.append("AND wd.catalog_node_id=? ");
            args.add(q.catalogNodeId());
        }
        if (q.createdBy() != null) {
            where.append("AND wd.created_by=? ");
            args.add(q.createdBy());
        }
        if (q.recentResult() != null && !q.recentResult().isBlank()) {
            if ("NEVER".equalsIgnoreCase(q.recentResult())) {
                where.append("AND NOT EXISTS (SELECT 1 FROM workflow_instance wi WHERE wi.workflow_id=wd.id) ");
            } else {
                where.append("AND ").append(recentSub).append("=? ");
                args.add(q.recentResult());
            }
        }
        if (q.priorityTier() != null && !q.priorityTier().isBlank()) {
            if ("high".equalsIgnoreCase(q.priorityTier())) {
                where.append("AND wd.priority BETWEEN 0 AND 2 ");
            } else if ("normal".equalsIgnoreCase(q.priorityTier())) {
                where.append("AND wd.priority BETWEEN 3 AND 9 ");
            }
        }
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM workflow_def wd" + where, Long.class, args.toArray());
        long totalCount = total == null ? 0L : total;
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) page * size);
        List<OpsContracts.WorkflowListRow> items = jdbc.query(
                "SELECT wd.id, wd.name, wd.description, wd.cron, wd.status, wd.current_version_no, "
                        + "wd.has_draft_change, wd.last_fire_time, wd.priority, wd.timeout_sec, "
                        + "wd.updated_at, wd.updated_by, wd.catalog_node_id, wd.next_trigger_time, "
                        + recentSub + " AS recent_result "
                        + "FROM workflow_def wd" + where
                        + orderByClause(q)
                        + " LIMIT ? OFFSET ?",
                (rs, n) -> {
                    LocalDateTime lastFire = rs.getObject("last_fire_time", LocalDateTime.class);
                    LocalDateTime updatedAt = rs.getObject("updated_at", LocalDateTime.class);
                    LocalDateTime nextTrigger = rs.getObject("next_trigger_time", LocalDateTime.class);
                    return new OpsContracts.WorkflowListRow(
                            (Long) rs.getObject("id"), rs.getString("name"), rs.getString("description"),
                            rs.getString("cron"), rs.getString("status"),
                            (Integer) rs.getObject("current_version_no"),
                            (Integer) rs.getObject("has_draft_change"),
                            lastFire != null ? lastFire.atZone(ZoneId.systemDefault()).toInstant().toString() : null,
                            (Integer) rs.getObject("priority"), (Integer) rs.getObject("timeout_sec"),
                            updatedAt != null ? updatedAt.atZone(ZoneId.systemDefault()).toInstant().toString() : null,
                            (Long) rs.getObject("updated_by"), (Long) rs.getObject("catalog_node_id"),
                            rs.getString("recent_result"),
                            nextTrigger != null ? nextTrigger.atZone(ZoneId.systemDefault()).toInstant().toString() : null);
                },
                pageArgs.toArray());
        return new OpsContracts.PageResult<>(items, totalCount, page, size);
    }

    /** 优先级排序子句（白名单防注入）：sortField=priority 时按 priority 排序 NULLS LAST，否则默认按 id。 */
    private String orderByClause(OpsContracts.WorkflowQuery q) {
        if (q.sortField() != null && "priority".equalsIgnoreCase(q.sortField())) {
            String dir = "desc".equalsIgnoreCase(q.sortDir()) ? "DESC" : "ASC";
            return " ORDER BY wd.priority " + dir + " NULLS LAST, wd.id";
        }
        return " ORDER BY wd.id";
    }

    /** 实例排序子句（白名单防注入）：sortField 映射为对应 DB 列，NULLS LAST，次级键 id 保证稳定。 */
    private String instanceOrderByClause(String alias, String sortField, String sortDir) {
        if (sortField == null || sortField.isBlank()) return null;
        String col = switch (sortField) {
            case "scheduledFireTime" -> "wi.scheduled_fire_time";
            case "bizDate" -> alias + ".biz_date";
            case "startedAt" -> alias + ".started_at";
            case "finishedAt" -> alias + ".finished_at";
            case "durationMs" -> "EXTRACT(EPOCH FROM (" + alias + ".finished_at - " + alias + ".started_at)) * 1000";
            default -> null;
        };
        if (col == null) return null;
        String dir = "asc".equalsIgnoreCase(sortDir) ? "ASC" : "DESC";
        return " ORDER BY " + col + " " + dir + " NULLS LAST, " + alias + ".id " + dir;
    }

    /** 有 sort 参数时用白名单排序，否则回退到默认优先级排序。 */
    private String orderByOrDefault(String alias, String sortField, String sortDir, String defaultOrderBy) {
        String dynamic = instanceOrderByClause(alias, sortField, sortDir);
        return dynamic != null ? dynamic : "ORDER BY CASE " + defaultOrderBy;
    }

    /**
     * 按 id 查单个任务实例（含 TEST 试跑）——日志流判定是否已结束用，不能用 {@link #instances()}（其排除 TEST）。
     */
    public java.util.Optional<TaskInstance> findInstance(UUID id) {
        return instanceRepository.findById(id);
    }

    /**
     * 正式运行实例（runMode=="NORMAL"，排除 TEST 试跑），按 id 降序。
     * 036 项目隔离：按 projectId 过滤，消除全表裸查。
     */
    public List<TaskInstance> instances(Long projectId) {
        if (projectId == null) {
            return StreamSupport.stream(instanceRepository.findAll().spliterator(), false)
                    .filter(i -> "NORMAL".equals(i.getRunMode()))
                    .sorted(Comparator.comparing(TaskInstance::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                    .collect(Collectors.toList());
        }
        return instanceRepository.findByProjectIdAndRunMode(projectId, "NORMAL").stream()
                .sorted(Comparator.comparing(TaskInstance::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());
    }

    /** 036 向后兼容：无参版本保留供内部调用，走全表（调度内核不接项目上下文）。 */
    public List<TaskInstance> instances() {
        return instances(null);
    }

    /** 040 日期筛选：按 projectId + bizDate 过滤（bizDate 非空时追加 WHERE biz_date=?，空时走原逻辑）。 */
    public List<TaskInstance> instances(Long projectId, String bizDate) {
        if (bizDate == null) {
            return instances(projectId);
        }
        if (projectId == null) {
            return StreamSupport.stream(instanceRepository.findAll().spliterator(), false)
                    .filter(i -> "NORMAL".equals(i.getRunMode()))
                    .filter(i -> bizDate.equals(i.getBizDate()))
                    .sorted(Comparator.comparing(TaskInstance::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                    .collect(Collectors.toList());
        }
        return instanceRepository.findByProjectIdAndRunModeAndBizDate(projectId, "NORMAL", bizDate).stream()
                .sorted(Comparator.comparing(TaskInstance::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());
    }

    /**
     * 某任务定义按 run_mode 过滤后的最近一个实例（供前端重开/刷新续接运行态）。
     * runMode 省略/空白默认 NORMAL；无实例返回 null。
     * 036 项目隔离：按 projectId 收敛结果。
     */
    public LatestInstanceView latestTaskInstance(Long taskDefId, String runMode, Long projectId) {
        String mode = (runMode == null || runMode.isBlank()) ? "NORMAL" : runMode;
        if (projectId != null) {
            return instanceRepository.findFirstByTaskIdAndRunModeAndProjectIdOrderByIdDesc(taskDefId, mode, projectId)
                    .map(ti -> new LatestInstanceView(ti.getId(), ti.getState(), ti.getRunMode()))
                    .orElse(null);
        }
        return instanceRepository.findFirstByTaskIdAndRunModeOrderByIdDesc(taskDefId, mode)
                .map(ti -> new LatestInstanceView(ti.getId(), ti.getState(), ti.getRunMode()))
                .orElse(null);
    }

    /** 某工作流定义的最近一个实例（供前端续接）；工作流实例恒 NORMAL；无实例返回 null。 */
    public LatestInstanceView latestWorkflowInstance(Long workflowId, Long projectId) {
        if (projectId != null) {
            return workflowInstanceRepository.findFirstByWorkflowIdAndProjectIdOrderByIdDesc(workflowId, projectId)
                    .map(wi -> new LatestInstanceView(wi.getId(), wi.getState(), "NORMAL"))
                    .orElse(null);
        }
        return workflowInstanceRepository.findFirstByWorkflowIdOrderByIdDesc(workflowId)
                .map(wi -> new LatestInstanceView(wi.getId(), wi.getState(), "NORMAL"))
                .orElse(null);
    }

    /** 失败的正式运行实例（state==FAILED && runMode==NORMAL）。036 项目隔离：按 projectId 过滤。 */
    public List<TaskInstance> failedInstances(Long projectId) {
        if (projectId == null) {
            return instanceRepository.findByState("FAILED").stream()
                    .filter(i -> "NORMAL".equals(i.getRunMode()))
                    .collect(Collectors.toList());
        }
        return instanceRepository.findByProjectIdAndState(projectId, "FAILED").stream()
                .filter(i -> "NORMAL".equals(i.getRunMode()))
                .collect(Collectors.toList());
    }

    /** 036 向后兼容：无参版本保留供内部调用。 */
    public List<TaskInstance> failedInstances() {
        return failedInstances(null);
    }

    /** 040 日期筛选：失败实例按 bizDate 过滤（bizDate 非空时追加 WHERE biz_date=?，空时走原逻辑）。 */
    public List<TaskInstance> failedInstances(Long projectId, String bizDate) {
        if (bizDate == null) {
            return failedInstances(projectId);
        }
        if (projectId == null) {
            return instanceRepository.findByState("FAILED").stream()
                    .filter(i -> "NORMAL".equals(i.getRunMode()))
                    .filter(i -> bizDate.equals(i.getBizDate()))
                    .collect(Collectors.toList());
        }
        return instanceRepository.findByProjectIdAndState(projectId, "FAILED").stream()
                .filter(i -> "NORMAL".equals(i.getRunMode()))
                .filter(i -> bizDate.equals(i.getBizDate()))
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
                    wi.getStartedAt() != null ? wi.getStartedAt().atZone(ZoneId.systemDefault()).toInstant().toString() : null,
                    wi.getFinishedAt() != null ? wi.getFinishedAt().atZone(ZoneId.systemDefault()).toInstant().toString() : null,
                    tasks, wi.getEnv());
        }).orElse(null);
    }

    /** 驾驶舱全局态势。036 项目隔离：按 projectId 收敛。040：bizDate 非空时按业务日期过滤。 */
    public DashboardSummary summary(Long projectId, String bizDate) {
        List<TaskInstance> all = bizDate != null ? instances(projectId, bizDate) : instances(projectId);
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
                failedInstances(projectId, bizDate));
    }

    // ─── 实例生命周期管理 ─────────────────────────────────

    /** 暂停工作流实例：RUNNING → PAUSED，所有 NOT_RUN 的 TaskInstance → PAUSED。 */
    /** DEV 实例仅允许停止操作，其他写操作拒绝（FR-013）。 */
    private void rejectDevEnv(WorkflowInstance wi, String operation) {
        if ("DEV".equals(wi.getEnv())) {
            throw new BizException("workflow.dev_limited", operation);
        }
    }

    /** 任务级 DEV 检查：通过 task -> workflow_instance 查 env。 */
    private void rejectDevEnvForTask(TaskInstance ti, String operation) {
        if (ti.getWorkflowInstanceId() != null) {
            workflowInstanceRepository.findById(ti.getWorkflowInstanceId()).ifPresent(wi -> {
                if ("DEV".equals(wi.getEnv())) {
                    throw new BizException("workflow.dev_limited", operation);
                }
            });
        }
    }

    public WorkflowInstance pauseWorkflow(UUID instanceId) {
        WorkflowInstance wi = workflowInstanceRepository.findById(instanceId)
                .orElseThrow(() -> new BizException("ops.instance.not_found", instanceId));
        rejectDevEnv(wi, "pause");
        if (!"RUNNING".equals(wi.getState())) {
            throw new BizException("ops.pause.only_running");
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
        audit("PAUSE_WORKFLOW", instanceId, "暂停工作流实例");
        return workflowInstanceRepository.save(wi);
    }

    /** 恢复工作流实例：PAUSED → RUNNING，所有 PAUSED 的 TaskInstance → NOT_RUN。 */
    public WorkflowInstance resumeWorkflow(UUID instanceId) {
        WorkflowInstance wi = workflowInstanceRepository.findById(instanceId)
                .orElseThrow(() -> new BizException("ops.instance.not_found", instanceId));
        rejectDevEnv(wi, "resume");
        if (!"PAUSED".equals(wi.getState())) {
            throw new BizException("ops.resume.only_paused");
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
        audit("RESUME_WORKFLOW", instanceId, "恢复工作流实例");
        return workflowInstanceRepository.save(wi);
    }

    /** 终止工作流实例：→ STOPPED，所有非终态 TaskInstance → STOPPED（经状态机 CAS 发状态事件→画布实时变色，并往各节点日志插手动停止行）。 */
    public WorkflowInstance killWorkflow(UUID instanceId) {
        WorkflowInstance wi = workflowInstanceRepository.findById(instanceId)
                .orElseThrow(() -> new BizException("ops.instance.not_found", instanceId));
        if (isTerminal(wi.getState())) {
            throw new BizException("ops.kill.terminal");
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
        audit("KILL_WORKFLOW", instanceId, "停止工作流实例");
        return workflowInstanceRepository.save(wi);
    }

    /** 暂停单个任务实例。 */
    public TaskInstance pauseTask(UUID instanceId) {
        TaskInstance ti = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new BizException("ops.task_instance.not_found", instanceId));
        if (!"NOT_RUN".equals(ti.getState())) {
            throw new BizException("ops.pause_task.only_not_run");
        }
        ti.setState("PAUSED");
        ti.setUpdatedAt(LocalDateTime.now());
        audit("PAUSE_TASK", instanceId, "暂停任务实例");
        return instanceRepository.save(ti);
    }

    /** 恢复单个任务实例。 */
    public TaskInstance resumeTask(UUID instanceId) {
        TaskInstance ti = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new BizException("ops.task_instance.not_found", instanceId));
        if (!"PAUSED".equals(ti.getState())) {
            throw new BizException("ops.resume_task.only_paused");
        }
        ti.setState("NOT_RUN");
        ti.setUpdatedAt(LocalDateTime.now());
        audit("RESUME_TASK", instanceId, "恢复任务实例");
        return instanceRepository.save(ti);
    }

    /** 终止单个任务实例：经状态机 CAS 置 STOPPED（发状态事件），并往其日志流插手动停止行。
     * 对 long_running 且有 external_job_handle 的实例，先尝试取消集群侧作业（FR-027）。 */
    public TaskInstance killTask(UUID instanceId) {
        TaskInstance ti = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new BizException("ops.task_instance.not_found", instanceId));
        if (isTerminal(ti.getState())) {
            throw new BizException("ops.kill_task.terminal");
        }
        // 060 FR-027: long_running + external_job_handle 非空 → 先取消集群侧作业
        cancelExternalJobIfNeeded(ti);
        if (stateMachine.casTaskTerminal(instanceId, ti.getState(), "STOPPED", "MANUAL_STOP")) {
            appendManualStopLog(instanceId);
            // 手动停单节点可能恰好是父流最后一个未完成节点：无其他后续事件会重算，须在此兜底聚合。
            if (ti.getWorkflowInstanceId() != null) {
                workflowStateService.computeAndUpdate(ti.getWorkflowInstanceId());
            }
        }
        // 返回最新态（CAS 已落库）。
        var latest = instanceRepository.findById(instanceId).orElse(ti);
        audit("KILL_TASK", instanceId, "停止任务实例");
        return latest;
    }

    /**
     * 060 FR-027：对 long_running 外部托管长驻作业，按 external_job_handle 取消集群侧作业。
     * 最佳努力（best-effort）——取消失败不影响主流程的 STOPPED CAS。
     */
    private void cancelExternalJobIfNeeded(TaskInstance ti) {
        if (ti.getTaskId() == null) return;
        // 读 task_def.long_running
        Boolean longRunning = jdbc.queryForObject(
                "SELECT long_running FROM task_def WHERE id=? AND deleted=0",
                Boolean.class, ti.getTaskId());
        if (!Boolean.TRUE.equals(longRunning)) return;

        String handle = null;
        // external_job_handle 可能尚未从 TaskInstance 领域对象加载，直接查 DB
        try {
            handle = jdbc.queryForObject(
                    "SELECT external_job_handle FROM task_instance WHERE id=? AND deleted=0",
                    String.class, ti.getId());
        } catch (Exception e) {
            // 列不存在或查询失败 → 跳过
            return;
        }
        if (handle == null || handle.isBlank()) return;

        // 尝试取消外部集群作业（best-effort，fire-and-forget）
        try {
            cancelFlinkJob(handle);
        } catch (Exception e) {
            // 取消失败不影响主流程
            log.log(System.Logger.Level.WARNING,
                    "killTask: 取消外部集群作业失败 instance={0}: {1}", ti.getId(), e.getMessage());
        }
    }

    /** 通过 Flink REST API 取消作业（best-effort）。 */
    private void cancelFlinkJob(String handleJson) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var node = mapper.readTree(handleJson);
            String jobId = node.has("jobId") ? node.get("jobId").asText() : null;
            String restEndpoint = node.has("restEndpoint") ? node.get("restEndpoint").asText() : null;
            if (jobId == null || restEndpoint == null) return;

            String url = restEndpoint + "/jobs/" + jobId;
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .method("PATCH", java.net.http.HttpRequest.BodyPublishers.ofString(
                            "{\"state\":\"CANCELED\"}"))
                    .header("Content-Type", "application/json")
                    .timeout(java.time.Duration.ofSeconds(10))
                    .build();
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(5))
                    .build();
            var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            log.log(System.Logger.Level.INFO,
                    "killTask: Flink job {0} cancel → HTTP {1}", jobId, response.statusCode());
        } catch (Exception e) {
            log.log(System.Logger.Level.WARNING,
                    "killTask: Flink REST cancel 失败: {0}", e.getMessage());
        }
    }

    // ─── data-ops-center：置成功 / 重跑 / 冻结 / 筛选 ──────────────

    /**
     * 置成功（set-success）：FAILED/STOPPED/RUNNING/PREEMPTED 经乐观 CAS → SUCCESS，发唤醒令下游就绪重算。
     * NOT_RUN/WAITING/PAUSED（无运行事实）拒绝。事务内只 CAS 落状态，唤醒在 CAS 成功后发出（死锁不变量④）。
     */
    public TaskInstance setSuccess(UUID instanceId) {
        TaskInstance ti = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new BizException("ops.task_instance.not_found", instanceId));
        rejectDevEnvForTask(ti, "set-success");
        String from = ti.getState();
        if (!SET_SUCCESS_FROM.contains(from)) {
            throw new BizException("ops.set_success.invalid_state", from);
        }
        if (stateMachine.casTaskTerminal(instanceId, from, InstanceStates.SUCCESS, null)) {
            // 唤醒：下游 WAITING 经调度认领的就绪门（上游 SUCCESS）自然解锁。
            eventBus.publish(InstanceStates.WAKE_CHANNEL, "set-success");
            // 强制置成功可能恰好是父流最后一个未完成节点：无其他后续事件会重算，须在此兜底聚合。
            if (ti.getWorkflowInstanceId() != null) {
                workflowStateService.computeAndUpdate(ti.getWorkflowInstanceId());
            }
        }
        audit("SET_SUCCESS", instanceId, "置成功任务实例");
        return instanceRepository.findById(instanceId).orElse(ti);
    }

    /**
     * 重跑（rerun）：终态/SUSPENDED 实例就地重置 WAITING（清 worker/租约/attempt/归因/时间/日志 + 重置双计数），
     * 发唤醒重新认领。节点隶属的工作流若已终态，一并回 RUNNING 让该节点可被认领。非终态且非 SUSPENDED 拒绝。
     */
    public TaskInstance rerunInstance(UUID instanceId) {
        TaskInstance ti = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new BizException("ops.task_instance.not_found", instanceId));
        rejectDevEnvForTask(ti, "rerun");
        String currentState = ti.getState();
        if (!isTerminal(currentState) && !"SUSPENDED".equals(currentState)) {
            throw new BizException("ops.rerun.not_terminal", currentState);
        }
        LocalDateTime now = LocalDateTime.now();
        // 060: 新增重置 business_attempt/infra_redispatch_count/external_job_handle（FR-028）
        jdbc.update("UPDATE task_instance SET state='WAITING', attempt=0, "
                + "business_attempt=0, infra_redispatch_count=0, external_job_handle=NULL, "
                + "worker_node_code=NULL, lease_expire_at=NULL, failure_reason=NULL, "
                + "finished_at=NULL, exit_code=NULL, started_at=NULL, log=NULL, updated_at=? "
                + "WHERE id=? AND deleted=0", now, instanceId);
        UUID wiId = ti.getWorkflowInstanceId();
        if (wiId != null) {
            int updated = jdbc.update("UPDATE workflow_instance SET state='RUNNING', finished_at=NULL, "
                    + "started_at=?, updated_at=? "
                    + "WHERE id=? AND state IN ('SUCCESS','FAILED','STOPPED') AND deleted=0", now, now, wiId);
            if (updated == 1) {
                // 原生 SQL 绕过 casWorkflowState，未发布 dw:evt：手动补发，避免实例详情视图停留旧态直到下次轮询。
                eventBus.publish("dw:evt:" + wiId, "{\"workflowState\":\"RUNNING\"}");
            }
        }
        eventBus.publish(InstanceStates.WAKE_CHANNEL, "rerun");
        audit("RERUN_INSTANCE", instanceId, "重跑任务实例");
        return instanceRepository.findById(instanceId).orElse(ti);
    }


    /** 周期实例多维筛选 + 分页（runMode/state/taskId/bizDate 任一为空即不约束；按 id 降序）。036 项目隔离：按 projectId 过滤。 */
    public PageResult<InstanceRow> queryInstances(InstanceQuery q) {
        int page = Math.max(0, q.page());
        int size = Math.min(Math.max(1, q.size()), 200);
        StringBuilder where = new StringBuilder(" WHERE ti.deleted=0 ");
        List<Object> args = new ArrayList<>();
        if (q.projectId() != null) {
            where.append("AND ti.project_id=? ");
            args.add(q.projectId());
        }
        if (q.runMode() != null && !q.runMode().isBlank()) {
            where.append("AND ti.run_mode=? ");
            args.add(q.runMode());
        }
        if (q.state() != null && !q.state().isBlank()) {
            where.append("AND ti.state=? ");
            args.add(q.state());
        }
        if (q.taskId() != null) {
            where.append("AND ti.task_id=? ");
            args.add(q.taskId());
        }
        if (q.bizDate() != null && !q.bizDate().isBlank()) {
            where.append("AND ti.biz_date=? ");
            args.add(q.bizDate());
        }
        // 扩展维度：状态多选（CSV）/ 业务日期区间 / 起止时间区间 / 执行节点 / 失败原因模糊
        if (q.stateIn() != null && !q.stateIn().isBlank()) {
            String[] states = q.stateIn().split(",");
            String ph = String.join(",", java.util.Collections.nCopies(states.length, "?"));
            where.append("AND ti.state IN (").append(ph).append(") ");
            for (String st : states) args.add(st.trim());
        }
        if (q.bizDateFrom() != null && !q.bizDateFrom().isBlank()) {
            where.append("AND ti.biz_date >= ? ");
            args.add(q.bizDateFrom().trim());
        }
        if (q.bizDateTo() != null && !q.bizDateTo().isBlank()) {
            where.append("AND ti.biz_date <= ? ");
            args.add(q.bizDateTo().trim());
        }
        if (q.startedAtFrom() != null && !q.startedAtFrom().isBlank()) {
            where.append("AND ti.started_at >= ? ");
            args.add(java.sql.Timestamp.valueOf(LocalDateTime.parse(q.startedAtFrom().trim())));
        }
        if (q.startedAtTo() != null && !q.startedAtTo().isBlank()) {
            where.append("AND ti.started_at <= ? ");
            args.add(java.sql.Timestamp.valueOf(LocalDateTime.parse(q.startedAtTo().trim())));
        }
        if (q.workerNodeCode() != null && !q.workerNodeCode().isBlank()) {
            where.append("AND ti.worker_node_code=? ");
            args.add(q.workerNodeCode().trim());
        }
        if (q.failureReason() != null && !q.failureReason().isBlank()) {
            where.append("AND ti.failure_reason LIKE CONCAT('%', ?, '%') ");
            args.add(q.failureReason().trim());
        }
        if (q.workflowInstanceId() != null) {
            where.append("AND ti.workflow_instance_id=? ");
            args.add(q.workflowInstanceId());
        }
        if (q.keyword() != null && !q.keyword().isBlank()) {
            where.append("AND (ti.task_def_name LIKE CONCAT('%', ?, '%') OR ti.workflow_def_name LIKE CONCAT('%', ?, '%')) ");
            String kw = q.keyword().trim();
            args.add(kw);
            args.add(kw);
        }
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_instance ti" + where, Long.class, args.toArray());
        long totalCount = total == null ? 0L : total;
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) page * size);
        List<InstanceRow> items = jdbc.query(
                "SELECT ti.id, ti.task_id, ti.workflow_instance_id, ti.run_mode, ti.state, ti.biz_date, "
                        + "ti.started_at, ti.finished_at, "
                        + "ti.task_def_name, ti.cron_expression, ti.env, ti.task_type, ti.workflow_def_name, "
                        + "wi.scheduled_fire_time, wi.trigger_type "
                        + "FROM task_instance ti "
                        + "LEFT JOIN workflow_instance wi ON ti.workflow_instance_id = wi.id" + where
                        + orderByOrDefault("ti", q.sortField(), q.sortDir(),
                                "  WHEN ti.state IN ('FAILED','KILLED','STOPPED','PREEMPTED') THEN 0 "
                                + "  WHEN ti.state IN ('RUNNING','DISPATCHED','WAITING','WAIT_RETRY') THEN 1 "
                                + "  ELSE 2 END, ti.id DESC")
                        + " LIMIT ? OFFSET ?",
                (rs, n) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    UUID wiId = rs.getObject("workflow_instance_id", UUID.class);
                    Long taskId = (Long) rs.getObject("task_id");
                    LocalDateTime startedAt = rs.getObject("started_at", LocalDateTime.class);
                    LocalDateTime finishedAt = rs.getObject("finished_at", LocalDateTime.class);
                    Long durationMs = (startedAt != null && finishedAt != null)
                            ? Duration.between(startedAt, finishedAt).toMillis() : null;
                    LocalDateTime sft = rs.getObject("scheduled_fire_time", LocalDateTime.class);
                    return new InstanceRow(id, taskId, rs.getString("task_def_name"), wiId,
                            rs.getString("run_mode"), rs.getString("state"), rs.getString("biz_date"),
                            startedAt != null ? startedAt.atZone(ZoneId.systemDefault()).toInstant().toString() : null,
                            finishedAt != null ? finishedAt.atZone(ZoneId.systemDefault()).toInstant().toString() : null,
                            durationMs, rs.getString("cron_expression"),
                            rs.getString("env"), rs.getString("task_type"), rs.getString("workflow_def_name"),
                            sft != null ? sft.atZone(ZoneId.systemDefault()).toInstant().toString() : null,
                            rs.getString("trigger_type"));
                },
                pageArgs.toArray());
        return new PageResult<>(items, totalCount, page, size);
    }

    /** 获取日志分块。 */
    public LogChunk getLog(UUID instanceId, int offset, int limit) {
        TaskInstance ti = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new BizException("ops.instance.not_found", instanceId));
        String log = ti.getLog();
        if (log == null || log.isEmpty()) {
            return new LogChunk("", 0, offset, false);
        }
        int totalSize = log.length();
        int end = Math.min(offset + limit, totalSize);
        String content = (offset < totalSize) ? log.substring(offset, end) : "";
        return new LogChunk(content, totalSize, offset, end < totalSize);
    }

    /**
     * 多维筛选 + 分页查询任务流实例列表。
     * JDBC 动态 SQL 模式，与 {@link #queryInstances} 一致，H2 + PostgreSQL 双兼容。
     * 036 项目隔离：按 projectId 过滤。
     */
    public OpsContracts.PageResult<OpsContracts.WorkflowInstanceRow> queryWorkflowInstances(
            OpsContracts.WorkflowInstanceQuery q) {
        int page = Math.max(0, q.page());
        int size = Math.min(Math.max(1, q.size()), 200);
        StringBuilder where = new StringBuilder(" WHERE wi.deleted=0 ");
        List<Object> args = new ArrayList<>();
        if (q.projectId() != null) {
            where.append("AND wi.project_id=? ");
            args.add(q.projectId());
        }
        if (q.state() != null && !q.state().isBlank()) {
            where.append("AND wi.state=? ");
            args.add(q.state());
        }
        if (q.stateIn() != null && !q.stateIn().isBlank()) {
            String[] states = q.stateIn().split(",");
            String ph = String.join(",", java.util.Collections.nCopies(states.length, "?"));
            where.append("AND wi.state IN (").append(ph).append(") ");
            for (String st : states) args.add(st.trim());
        }
        if (q.triggerType() != null && !q.triggerType().isBlank()) {
            where.append("AND wi.trigger_type=? ");
            args.add(q.triggerType());
        }
        if (q.workflowId() != null) {
            where.append("AND wi.workflow_id=? ");
            args.add(q.workflowId());
        }
        if (q.bizDate() != null && !q.bizDate().isBlank()) {
            where.append("AND wi.biz_date=? ");
            args.add(q.bizDate());
        }
        if (q.bizDateFrom() != null && !q.bizDateFrom().isBlank()) {
            where.append("AND wi.biz_date >= ? ");
            args.add(q.bizDateFrom().trim());
        }
        if (q.bizDateTo() != null && !q.bizDateTo().isBlank()) {
            where.append("AND wi.biz_date <= ? ");
            args.add(q.bizDateTo().trim());
        }
        if (q.startedAtFrom() != null && !q.startedAtFrom().isBlank()) {
            where.append("AND wi.started_at >= ? ");
            args.add(java.sql.Timestamp.valueOf(LocalDateTime.parse(q.startedAtFrom().trim())));
        }
        if (q.startedAtTo() != null && !q.startedAtTo().isBlank()) {
            where.append("AND wi.started_at <= ? ");
            args.add(java.sql.Timestamp.valueOf(LocalDateTime.parse(q.startedAtTo().trim())));
        }
        if (q.scheduledFireTimeFrom() != null && !q.scheduledFireTimeFrom().isBlank()) {
            where.append("AND wi.scheduled_fire_time >= ? ");
            args.add(java.sql.Timestamp.valueOf(LocalDateTime.parse(q.scheduledFireTimeFrom().trim())));
        }
        if (q.scheduledFireTimeTo() != null && !q.scheduledFireTimeTo().isBlank()) {
            where.append("AND wi.scheduled_fire_time <= ? ");
            args.add(java.sql.Timestamp.valueOf(LocalDateTime.parse(q.scheduledFireTimeTo().trim())));
        }
        if (q.keyword() != null && !q.keyword().isBlank()) {
            // 单框合一：关键字同时匹配任务流名称(workflow_def_name 物化快照) 与 实例 ID。
            // id 是 UUID，CAST 成 VARCHAR 再做模糊匹配，H2 + PostgreSQL 双兼容；
            // 前端只展示后 8 位 …xxxxxxxx，故按片段模糊（用户无需完整 UUID）。
            where.append("AND (wi.workflow_def_name LIKE CONCAT('%', ?, '%') "
                    + "OR CAST(wi.id AS VARCHAR) LIKE CONCAT('%', ?, '%')) ");
            String kw = q.keyword().trim();
            args.add(kw);
            args.add(kw);
        }

        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM workflow_instance wi" + where, Long.class, args.toArray());
        long totalCount = total == null ? 0L : total;

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) page * size);
        List<OpsContracts.WorkflowInstanceRow> items = jdbc.query(
                "SELECT wi.id, wi.workflow_id, wi.trigger_type, wi.state, wi.biz_date, "
                        + "wi.total_tasks, wi.completed_tasks, wi.failed_tasks, "
                        + "wi.started_at, wi.finished_at, wi.priority, wi.env, "
                        + "wi.workflow_def_name, wi.cron_expression, "
                        + "wi.workflow_version_no, wi.scheduled_fire_time "
                        + "FROM workflow_instance wi" + where
                        + orderByOrDefault("wi", q.sortField(), q.sortDir(),
                                "  WHEN wi.state IN ('FAILED','STOPPED','PREEMPTED') THEN 0 "
                                + "  WHEN wi.state IN ('RUNNING') THEN 1 "
                                + "  ELSE 2 END, wi.id DESC")
                        + " LIMIT ? OFFSET ?",
                (rs, n) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    Long wfId = rs.getLong("workflow_id");
                    Integer totalTasks = rs.getInt("total_tasks");
                    Integer completedTasks = rs.getInt("completed_tasks");
                    Integer failedTasks = rs.getInt("failed_tasks");
                    Integer priority = rs.getInt("priority");
                    LocalDateTime startedAt = rs.getObject("started_at", LocalDateTime.class);
                    LocalDateTime finishedAt = rs.getObject("finished_at", LocalDateTime.class);
                    Integer workflowVersionNo = (Integer) rs.getObject("workflow_version_no");
                    LocalDateTime scheduledFireTime = rs.getObject("scheduled_fire_time", LocalDateTime.class);
                    Long durationMs = (startedAt != null && finishedAt != null)
                            ? Duration.between(startedAt, finishedAt).toMillis() : null;
                    return new OpsContracts.WorkflowInstanceRow(id, wfId,
                            rs.getString("workflow_def_name"),
                            rs.getString("state"), rs.getString("biz_date"),
                            priority, rs.getString("trigger_type"),
                            totalTasks != null ? totalTasks : 0,
                            completedTasks != null ? completedTasks : 0,
                            failedTasks != null ? failedTasks : 0,
                            startedAt != null ? startedAt.atZone(ZoneId.systemDefault()).toInstant().toString() : null,
                            finishedAt != null ? finishedAt.atZone(ZoneId.systemDefault()).toInstant().toString() : null,
                            durationMs, rs.getString("env"),
                            workflowVersionNo, rs.getString("cron_expression"),
                            scheduledFireTime != null ? scheduledFireTime.atZone(ZoneId.systemDefault()).toInstant().toString() : null);
                },
                pageArgs.toArray());
        return new OpsContracts.PageResult<>(items, totalCount, page, size);
    }

    /**
     * 获取实例级 DAG 视图：历史拓扑（workflow_def_version.dag_snapshot_json）+
     * task_instance 运行时状态叠加。
     */
    public OpsContracts.InstanceDagView getInstanceDag(UUID workflowInstanceId) {
        // 1. 查 WorkflowInstance 元信息
        var wiRows = jdbc.query(
                "SELECT wi.id, wi.workflow_id, wi.workflow_version_no, wi.trigger_type, wi.state, wi.biz_date, "
                        + "wi.env, wi.workflow_def_name "
                        + "FROM workflow_instance wi WHERE wi.id=? AND wi.deleted=0",
                (rs, n) -> List.<Object>of(
                        rs.getObject("id", UUID.class),
                        rs.getLong("workflow_id"),
                        rs.getInt("workflow_version_no"),
                        rs.getString("trigger_type"),
                        rs.getString("state"),
                        rs.getString("biz_date"),
                        rs.getString("env"),
                        rs.getString("workflow_def_name")),
                workflowInstanceId);
        if (wiRows.isEmpty()) {
            throw new BizException("ops.instance.not_found", workflowInstanceId);
        }
        var row = wiRows.get(0);
        UUID wiId = (UUID) row.get(0);
        Long workflowId = (Long) row.get(1);
        Integer versionNo = (Integer) row.get(2);
        String triggerType = (String) row.get(3);
        String wiState = (String) row.get(4);
        String bizDate = (String) row.get(5);
        String env = (String) row.get(6);
        String workflowName = (String) row.get(7);

        // 2. 查历史版本 DAG snapshot
        List<String> dagRows = jdbc.query(
                "SELECT wdv.dag_snapshot_json FROM workflow_def_version wdv "
                        + "WHERE wdv.workflow_id=? AND wdv.version_no=?",
                (rs, n) -> rs.getString("dag_snapshot_json"),
                workflowId, versionNo);
        if (dagRows.isEmpty() || dagRows.get(0) == null || dagRows.get(0).isBlank()) {
            throw new BizException("workflow.dag_snapshot_missing", workflowId, versionNo.toString());
        }
        String dagJson = dagRows.get(0);

        // 3. 反序列化 DAG 拓扑
        com.dataweave.master.domain.WorkflowDagSnapshot snapshot;
        try {
            snapshot = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(dagJson, com.dataweave.master.domain.WorkflowDagSnapshot.class);
        } catch (Exception e) {
            throw new BizException("workflow.dag_snapshot_corrupt", workflowId, versionNo.toString());
        }

        // 4. 查 task_instance 运行时状态
        List<Object[]> tiRows = jdbc.query(
                "SELECT ti.id, ti.task_id, ti.state, ti.attempt, ti.started_at, ti.finished_at "
                        + "FROM task_instance ti WHERE ti.workflow_instance_id=? AND ti.deleted=0",
                (rs, n) -> {
                    UUID tiId = rs.getObject("id", UUID.class);
                    return new Object[]{
                            tiId,
                            rs.getLong("task_id"),
                            rs.getString("state"),
                            rs.getInt("attempt"),
                            rs.getObject("started_at", LocalDateTime.class),
                            rs.getObject("finished_at", LocalDateTime.class)};
                },
                workflowInstanceId);

        // 构建 taskId → task instance 状态映射
        record TiState(UUID id, String state, int attempt, LocalDateTime startedAt,
                       LocalDateTime finishedAt) {}
        java.util.Map<Long, TiState> stateMap = new java.util.LinkedHashMap<>();
        for (Object[] t : tiRows) {
            Long tid = (Long) t[1];
            // 同一 taskId 下取最新（按 id 倒序），实际场景极少重复
            stateMap.putIfAbsent(tid, new TiState(
                    (UUID) t[0], (String) t[2], (Integer) t[3],
                    (LocalDateTime) t[4], (LocalDateTime) t[5]));
        }

        // 5. 批量查 task_def 名称（snapshot 中 name 可能为 null）
        java.util.Map<Long, String> taskNameMap = new java.util.LinkedHashMap<>();
        List<Long> taskIds = snapshot.nodes().stream()
                .map(com.dataweave.master.domain.WorkflowDagSnapshot.Node::taskId)
                .filter(id -> id != null)
                .distinct()
                .toList();
        if (!taskIds.isEmpty()) {
            String ph = taskIds.stream().map(id -> "?").collect(java.util.stream.Collectors.joining(","));
            List<Object[]> nameRows = jdbc.query(
                    "SELECT id, name FROM task_def WHERE id IN (" + ph + ")",
                    (rs, n) -> new Object[]{rs.getLong("id"), rs.getString("name")},
                    taskIds.toArray());
            for (Object[] nr : nameRows) {
                taskNameMap.put((Long) nr[0], (String) nr[1]);
            }
        }

        // 6. 自动布局：snapshot 坐标全部为空/0 时，按 DAG 拓扑自动计算坐标
        boolean hasCoords = snapshot.nodes().stream()
                .anyMatch(n -> (n.posX() != null && n.posX() != 0) || (n.posY() != null && n.posY() != 0));
        java.util.Map<String, double[]> layout = new java.util.LinkedHashMap<>();
        if (!hasCoords && !snapshot.nodes().isEmpty()) {
            // 构建邻接表（from → to）+ 入度
            java.util.Map<String, java.util.List<String>> adj = new java.util.LinkedHashMap<>();
            java.util.Map<String, Integer> indeg = new java.util.LinkedHashMap<>();
            for (var sn : snapshot.nodes()) {
                adj.putIfAbsent(sn.nodeKey(), new ArrayList<>());
                indeg.putIfAbsent(sn.nodeKey(), 0);
            }
            for (var e : snapshot.edges()) {
                adj.computeIfAbsent(e.fromNodeKey(), k -> new ArrayList<>()).add(e.toNodeKey());
                indeg.merge(e.toNodeKey(), 1, Integer::sum);
                indeg.putIfAbsent(e.fromNodeKey(), indeg.getOrDefault(e.fromNodeKey(), 0));
            }
            // BFS 分层：入度 0 的节点为 layer 0
            java.util.Map<String, Integer> layer = new java.util.LinkedHashMap<>();
            java.util.Queue<String> queue = new java.util.LinkedList<>();
            for (var sn : snapshot.nodes()) {
                if (indeg.getOrDefault(sn.nodeKey(), 0) == 0) {
                    queue.add(sn.nodeKey());
                    layer.put(sn.nodeKey(), 0);
                }
            }
            while (!queue.isEmpty()) {
                String u = queue.poll();
                int cur = layer.get(u);
                for (String v : adj.getOrDefault(u, List.of())) {
                    int newLayer = Math.max(layer.getOrDefault(v, 0), cur + 1);
                    layer.put(v, newLayer);
                    indeg.merge(v, -1, Integer::sum);
                    if (indeg.get(v) <= 0) queue.add(v);
                }
            }
            // 每层节点按层内位置纵向分布
            java.util.Map<Integer, java.util.List<String>> byLayer = new java.util.LinkedHashMap<>();
            for (var e : layer.entrySet()) {
                byLayer.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
            }
            final double H_SPACING = 220.0;
            final double V_SPACING = 100.0;
            for (var ble : byLayer.entrySet()) {
                int l = ble.getKey();
                var keys = ble.getValue();
                double x = 50.0 + l * H_SPACING;
                double startY = 50.0 + (byLayer.size() > 1 ? (keys.size() - 1) * V_SPACING / -2.0 : 0);
                for (int i = 0; i < keys.size(); i++) {
                    layout.put(keys.get(i), new double[]{x, startY + i * V_SPACING});
                }
            }
        }

        // 7. 组装 InstanceDagView
        List<OpsContracts.InstanceDagNode> nodes = new ArrayList<>();
        for (var sn : snapshot.nodes()) {
            TiState ts = stateMap.get(sn.taskId());
            String nodeState = ts != null ? ts.state() : "NOT_RUN";
            UUID tiId = ts != null ? ts.id() : null;
            int attempt = ts != null ? ts.attempt() : 0;
            String startedAt = ts != null && ts.startedAt() != null ? ts.startedAt().atZone(ZoneId.systemDefault()).toInstant().toString() : null;
            String finishedAt = ts != null && ts.finishedAt() != null ? ts.finishedAt().atZone(ZoneId.systemDefault()).toInstant().toString() : null;
            Long durationMs = (ts != null && ts.startedAt() != null && ts.finishedAt() != null)
                    ? Duration.between(ts.startedAt(), ts.finishedAt()).toMillis() : null;
            double posX = sn.posX() != null ? sn.posX().doubleValue() : 0.0;
            double posY = sn.posY() != null ? sn.posY().doubleValue() : 0.0;
            if (!hasCoords && layout.containsKey(sn.nodeKey())) {
                double[] lp = layout.get(sn.nodeKey());
                posX = lp[0];
                posY = lp[1];
            }
            // name: snapshot 优先，回退 task_def.name
            String nodeName = sn.name() != null && !sn.name().isBlank()
                    ? sn.name() : taskNameMap.getOrDefault(sn.taskId(), "task-" + sn.taskId());
            nodes.add(new OpsContracts.InstanceDagNode(sn.nodeKey(), nodeName, sn.taskId(), tiId,
                    nodeState, attempt, startedAt, finishedAt, durationMs, posX, posY,
                    sn.nodeType() != null ? sn.nodeType() : "TASK"));
        }

        List<OpsContracts.InstanceDagEdge> edges = snapshot.edges().stream()
                .map(e -> new OpsContracts.InstanceDagEdge(e.fromNodeKey(), e.toNodeKey(),
                        e.strength() != null ? e.strength() : "NORMAL"))
                .toList();

        return new OpsContracts.InstanceDagView(wiId, workflowName, versionNo != null ? versionNo : 0,
                triggerType, wiState, bizDate, nodes, edges, env);
    }

    /**
     * 获取任务实例的参数替换后实际代码。
     * 内容优先级：content_override (TEST) > task_def_version.content > task_def.content。
     * 参数替换复用 {@link ScheduleParamResolver}，保证展示结果与执行时一致。
     */
    public OpsContracts.ResolvedCodeView resolveActualCode(UUID taskInstanceId) {
        // 查 task_instance 关键字段
        var tiRow = jdbc.queryForList(
                "SELECT ti.id, ti.task_id, ti.biz_date, ti.run_mode, ti.content_override, ti.params_override, "
                        + "ti.task_version_no, ti.workflow_instance_id "
                        + "FROM task_instance ti WHERE ti.id=? AND ti.deleted=0",
                taskInstanceId);
        if (tiRow.isEmpty()) {
            throw new BizException("ops.instance.not_found", taskInstanceId);
        }
        var r = tiRow.get(0);
        UUID id = (UUID) r.get("id");
        Long taskId = (Long) r.get("task_id");
        String bizDate = (String) r.get("biz_date");
        String runMode = (String) r.get("run_mode");
        String contentOverride = (String) r.get("content_override");
        String paramsOverride = (String) r.get("params_override");
        Integer taskVersionNo = (Integer) r.get("task_version_no");
        UUID workflowInstanceId = (UUID) r.get("workflow_instance_id");

        // 获取原始模板和参数（按优先级）
        String rawContent = null;
        String paramsJson = null;
        boolean isOverride = false;

        if (contentOverride != null && !contentOverride.isBlank()) {
            rawContent = contentOverride;
            paramsJson = paramsOverride;
            isOverride = true;
        } else if (taskVersionNo != null) {
            // 从已发布版本快照取
            String vContent = jdbc.queryForObject(
                    "SELECT tdv.content FROM task_def_version tdv WHERE tdv.task_id=? AND tdv.version_no=?",
                    String.class, taskId, taskVersionNo);
            String vParams = jdbc.queryForObject(
                    "SELECT tdv.params_json FROM task_def_version tdv WHERE tdv.task_id=? AND tdv.version_no=?",
                    String.class, taskId, taskVersionNo);
            rawContent = vContent;
            paramsJson = vParams;
        }

        if (rawContent == null || rawContent.isBlank()) {
            // 回退到 task_def 当前内容
            rawContent = jdbc.queryForObject(
                    "SELECT td.content FROM task_def td WHERE td.id=?", String.class, taskId);
            paramsJson = jdbc.queryForObject(
                    "SELECT td.params_json FROM task_def td WHERE td.id=?", String.class, taskId);
        }

        if (rawContent == null || rawContent.isBlank()) {
            throw new BizException("ops.instance.no_content", taskInstanceId);
        }

        // 获取 task type
        String taskType = jdbc.queryForObject(
                "SELECT COALESCE((SELECT tdv.type FROM task_def_version tdv WHERE tdv.task_id=? AND tdv.version_no=?), "
                        + "(SELECT td.type FROM task_def td WHERE td.id=?))",
                String.class, taskId, taskVersionNo, taskId);

        // 参数替换
        ScheduleParamResolver resolver = new ScheduleParamResolver();
        String resolvedContent;
        List<String> unresolved = new ArrayList<>();
        try {
            ScheduleParamResolver.BuiltInContext ctx = new ScheduleParamResolver.BuiltInContext(
                    workflowInstanceId != null ? workflowInstanceId.toString() : "",
                    "",
                    id.toString(),
                    LocalDate.now());
            resolvedContent = resolver.resolve(rawContent, bizDate, paramsJson, ctx);
        } catch (ScheduleParamResolver.UnresolvedPlaceholderException e) {
            resolvedContent = rawContent;
            unresolved.add(e.getCode());
        } catch (Exception e) {
            resolvedContent = rawContent;
            unresolved.add("resolution_error");
        }

        return new OpsContracts.ResolvedCodeView(id, rawContent, resolvedContent, unresolved,
                runMode != null ? runMode : "NORMAL", isOverride,
                taskType != null ? taskType : "UNKNOWN");
    }

    /**
     * 获取任务实例的参数替换后实际配置。
     */
    public OpsContracts.ResolvedConfigView resolveActualConfig(UUID taskInstanceId) {
        var tiRow = jdbc.queryForList(
                "SELECT ti.id, ti.task_id, ti.run_mode, ti.params_override, ti.task_version_no, "
                        + "ti.content_override "
                        + "FROM task_instance ti WHERE ti.id=? AND ti.deleted=0",
                taskInstanceId);
        if (tiRow.isEmpty()) {
            throw new BizException("ops.instance.not_found", taskInstanceId);
        }
        var r = tiRow.get(0);
        UUID id = (UUID) r.get("id");
        Long taskId = (Long) r.get("task_id");
        String runMode = (String) r.get("run_mode");
        String paramsOverride = (String) r.get("params_override");
        Integer taskVersionNo = (Integer) r.get("task_version_no");
        String contentOverride = (String) r.get("content_override");
        boolean isOverride = contentOverride != null && !contentOverride.isBlank();

        // 获取 task type
        String taskType = jdbc.queryForObject(
                "SELECT COALESCE((SELECT tdv.type FROM task_def_version tdv WHERE tdv.task_id=? AND tdv.version_no=?), "
                        + "(SELECT td.type FROM task_def td WHERE td.id=?))",
                String.class, taskId, taskVersionNo, taskId);
        if (taskType == null) taskType = "UNKNOWN";

        // 获取默认配置
        Integer timeoutSec = jdbc.queryForObject(
                "SELECT COALESCE((SELECT tdv.timeout_sec FROM task_def_version tdv WHERE tdv.task_id=? AND tdv.version_no=?), "
                        + "(SELECT td.timeout_sec FROM task_def td WHERE td.id=?))",
                Integer.class, taskId, taskVersionNo, taskId);
        Integer retryMax = jdbc.queryForObject(
                "SELECT COALESCE((SELECT tdv.retry_max FROM task_def_version tdv WHERE tdv.task_id=? AND tdv.version_no=?), "
                        + "(SELECT td.retry_max FROM task_def td WHERE td.id=?))",
                Integer.class, taskId, taskVersionNo, taskId);
        String versionParams = jdbc.queryForObject(
                "SELECT COALESCE((SELECT tdv.params_json FROM task_def_version tdv WHERE tdv.task_id=? AND tdv.version_no=?), "
                        + "(SELECT td.params_json FROM task_def td WHERE td.id=?))",
                String.class, taskId, taskVersionNo, taskId);
        String originalParamsJson = (!isOverride) ? null : versionParams;

        // 实际使用的 params（TEST 覆盖 > 版本 > 当前定义）
        String rawParamsJson = (paramsOverride != null && !paramsOverride.isBlank())
                ? paramsOverride : versionParams;

        int timeout = timeoutSec != null ? timeoutSec : 300;
        int retry = retryMax != null ? retryMax : 0;
        String retryStrategy = "FIXED(" + retry + ",60s)";
        String resourceLimit = "cpu=2,mem=1024Mi";

        // 参数替换
        ScheduleParamResolver resolver = new ScheduleParamResolver();
        String resolvedParamsJson = rawParamsJson;
        List<String> unresolved = new ArrayList<>();
        if (rawParamsJson != null && !rawParamsJson.isBlank()) {
            try {
                resolvedParamsJson = resolver.resolve(rawParamsJson, "",
                        rawParamsJson, new ScheduleParamResolver.BuiltInContext("", "", id.toString(), LocalDate.now()));
            } catch (Exception e) {
                unresolved.add("resolution_error");
            }
        }

        return new OpsContracts.ResolvedConfigView(id, taskType, timeout, retryStrategy, resourceLimit,
                rawParamsJson != null ? rawParamsJson : "{}",
                resolvedParamsJson != null ? resolvedParamsJson : "{}",
                unresolved, runMode != null ? runMode : "NORMAL", isOverride,
                originalParamsJson, isOverride ? timeout : null,
                taskVersionNo != null ? taskVersionNo : 0);
    }

    public record LogChunk(String content, int totalSize, int offset, boolean hasMore) {}

    /** 最近活跃实例视图：id + 状态 + 运行模式（工作流恒 NORMAL）。供前端按状态决定续接与按钮态。 */
    public record LatestInstanceView(UUID id, String state, String runMode) {}

    public record TaskNodeView(UUID id, Long taskDefId, String taskDefName, String state,
            String workerNodeCode, Integer attempt) {}

    /** 工作流实例详情（实例 + 任务节点视图）。runMode 恒 NORMAL（试跑不建 workflow_instance）。 */
    public record WorkflowInstanceDetail(UUID id, Long workflowId, String bizDate, String state,
            Integer priority, String runMode, String startedAt, String finishedAt,
            List<TaskNodeView> tasks, String env) {}

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
     */
    public record DashboardSummary(int total, int success, int failed, int running,
                                   List<TaskInstance> failedInstances) {
    }
}
