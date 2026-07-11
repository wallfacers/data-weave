package com.dataweave.master.application;

import com.dataweave.master.application.readiness.ReadinessSignalWriter;
import com.dataweave.master.application.lineage.LineageEdgeAssembler;
import com.dataweave.master.domain.EventBus;
import com.dataweave.master.domain.InstanceStates;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.TaskInstanceRepository;
import com.dataweave.master.domain.lineage.DatasourceCoord;
import com.dataweave.master.domain.lineage.LineageStore;
import com.dataweave.master.domain.lineage.StatementMetric;
import com.dataweave.master.domain.lineage.TableRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * worker 执行回报入口（task 3.4）：started / finished / failed 三态回调，CAS 推进任务实例 +
 * 重算工作流聚合态 + 失败级联取消下游 + 发布唤醒触发下一轮调度。
 *
 * <p>all-in-one 由 {@code InProcessTaskExecutionGateway} 进程内调用；distributed 由 worker HTTP 回调
 * 任一 master 触达本服务（共享 token 鉴权）。所有状态推进走乐观 CAS，竞态（如抢占与完成）由 DB 裁决。
 */
@Service
public class WorkerReportService {

    private static final Logger log = LoggerFactory.getLogger(WorkerReportService.class);

    private final InstanceStateMachine stateMachine;
    private final TaskInstanceRepository taskInstanceRepository;
    private final WorkflowStateService workflowStateService;
    private final RetryService retryService;
    private final SchedulerMetrics metrics;
    private final SlaService slaService;
    private final EventBus eventBus;
    private final ApplicationEventPublisher eventPublisher;
    private final JdbcTemplate jdbc;
    private final LineageStore lineageStore;
    private final SqlTableExtractor sqlTableExtractor;
    private final LineageEdgeAssembler lineageEdgeAssembler;
    private final ReadinessSignalWriter readinessSignalWriter;
    private final NodeHealthService nodeHealthService;
    private final TransactionTemplate txTemplate;

    public WorkerReportService(InstanceStateMachine stateMachine,
                               TaskInstanceRepository taskInstanceRepository,
                               WorkflowStateService workflowStateService,
                               RetryService retryService,
                               SchedulerMetrics metrics,
                               SlaService slaService,
                               EventBus eventBus,
                               ApplicationEventPublisher eventPublisher,
                               JdbcTemplate jdbc,
                               LineageStore lineageStore,
                               SqlTableExtractor sqlTableExtractor,
                               LineageEdgeAssembler lineageEdgeAssembler,
                               ReadinessSignalWriter readinessSignalWriter,
                               NodeHealthService nodeHealthService,
                               PlatformTransactionManager txManager) {
        this.stateMachine = stateMachine;
        this.taskInstanceRepository = taskInstanceRepository;
        this.workflowStateService = workflowStateService;
        this.retryService = retryService;
        this.metrics = metrics;
        this.slaService = slaService;
        this.eventBus = eventBus;
        this.eventPublisher = eventPublisher;
        this.jdbc = jdbc;
        this.lineageStore = lineageStore;
        this.sqlTableExtractor = sqlTableExtractor;
        this.lineageEdgeAssembler = lineageEdgeAssembler;
        this.readinessSignalWriter = readinessSignalWriter;
        this.nodeHealthService = nodeHealthService;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    /**
     * worker 开始执行：DISPATCHED → RUNNING。
     *
     * @return CAS 是否成功（true=本次由 DISPATCHED 推进到 RUNNING；false=任务已非 DISPATCHED，
     *         如已被 LeaseReaper 回收重派 / 已终态）。调用方据此做 fencing：CAS 失败应中止执行，
     *         避免陈旧下发与重派命令同实例双跑（配合 {@link InstanceStateMachine#isCurrentDispatch} 放宽后的收口）。
     */
    public boolean reportStarted(UUID taskInstanceId) {
        boolean started = stateMachine.casTaskState(taskInstanceId, InstanceStates.DISPATCHED, InstanceStates.RUNNING);
        if (started) {
            jdbc.update("UPDATE task_instance SET started_at=? WHERE id=? AND started_at IS NULL",
                    LocalDateTime.now(), taskInstanceId);
            // 记录下发延迟（DISPATCHED → RUNNING）
            recordDeliveryLatency(taskInstanceId);
        }
        wake();
        return started;
    }

    /**
     * 060 FR-023：worker 侧 Flink detached 提交流式作业后，回写 external_job_handle（JobID+REST 端点）。
     *
     * <p>仅对活跃态（DISPATCHED/RUNNING）实例落库——防止终态/回收后的迟到回写复活句柄。
     * failover 时新 worker 据此 reattach 不重复提交；人工 kill 据此取消集群作业（{@code OpsService.killTask}）。
     *
     * @return true=已落库（实例活跃）；false=实例非活跃/不存在（回写被忽略）
     */
    public boolean recordExternalJobHandle(UUID taskInstanceId, String handle) {
        if (taskInstanceId == null || handle == null || handle.isBlank()) {
            return false;
        }
        // D2：新句柄写入即消费 resume_checkpoint_id —— savepoint 恢复的全新提交已把状态载入新作业，
        // 后续 infra-redispatch 应 reattach 到新作业（external_job_handle）而非重复从同一 savepoint 恢复。
        int n = jdbc.update(
                "UPDATE task_instance SET external_job_handle=?, resume_checkpoint_id=NULL, updated_at=? "
                        + "WHERE id=? AND deleted=0 AND state IN ('DISPATCHED','RUNNING')",
                handle, LocalDateTime.now(), taskInstanceId);
        return n == 1;
    }

    /** worker 执行成功：→ SUCCESS，回写日志/退出码，重算工作流聚合态。 */
    public void reportFinished(UUID taskInstanceId, Integer exitCode, String tailLog,
                               List<StatementMetric> statementMetrics) {
        TaskInstance ti = taskInstanceRepository.findById(taskInstanceId).orElse(null);
        if (ti == null) {
            return;
        }

        // 终态推进 + 信号 append 同事务（R4 no-loss：信号与完成同提交，崩溃不丢）。
        // casTaskTerminalFromActive 不依赖外部 fromState 快照——WHERE state IN ('DISPATCHED','RUNNING')
        // 由 DB 裁决，闭合与 LeaseReaper 的 TOCTOU 竞态（worker 真执行完成不因租约回收假阴性丢回报）。
        boolean ok = Boolean.TRUE.equals(txTemplate.execute(status -> {
            if (!stateMachine.casTaskTerminalFromActive(taskInstanceId, InstanceStates.SUCCESS, null)) {
                return false;
            }
            writeTerminalSignal(ti);   // 同事务 INSERT readiness_signal；异常 → 回滚 casTaskTerminal → no-loss
            return true;
        }));
        if (!ok) {
            wake();
            return;
        }

        writeLog(taskInstanceId, exitCode, tailLog);          // 事务外（辅助）
        recordTaskCompletion(taskInstanceId, "SUCCESS");      // 事务外（指标）
        recordSyncedRows(ti, statementMetrics);               // 事务外（neo4j 降级零阻断，不应进正确性事务）
        // 060（FR-004）：节点成功执行一次 → 解除熔断计数复位（成功是节点恢复候选资格的信号）。
        if (ti.getWorkerNodeCode() != null) {
            nodeHealthService.clearOnSuccess(ti.getWorkerNodeCode());
        }
        recomputeWorkflow(ti.getWorkflowInstanceId());        // 事务外（级联 STOPPED + 聚合，长路径）
        wake();
    }

    /**
     * feature 025：逐 statement 解析写表 → {@code recordSynced}（运行态同步行数）。
     *
     * <p>降级零阻断（FR-005）：{@code updateCount<0}（SELECT/DDL）跳过；解析不出写表 + {@code updateCount>0}
     * → WARN（不静默丢）；statementMetrics null/empty（旧 worker）跳过；neo4j 不可达/异常 → 外层 try-catch 吞，
     * 任务仍 SUCCESS。coord 复用 {@link LineageEdgeAssembler#resolveCoord}（与设计态同源 → runtime :Table 同 tableKey）。
     */
    private void recordSyncedRows(TaskInstance ti, List<StatementMetric> statementMetrics) {
        if (statementMetrics == null || statementMetrics.isEmpty()
                || ti.getTenantId() == null || ti.getProjectId() == null || ti.getBizDate() == null) {
            return;
        }
        long tenantId = ti.getTenantId();
        long projectId = ti.getProjectId();
        Long taskDefId = ti.getTaskId();
        String instanceId = String.valueOf(ti.getId());
        String bizDate = ti.getBizDate();
        DatasourceCoord writeCoord = resolveWriteCoord(tenantId, projectId, taskDefId);
        try {
            for (StatementMetric m : statementMetrics) {
                if (m == null || m.updateCount() < 0) {
                    continue;   // SELECT/DDL 跳过
                }
                SqlTableExtractor.Result parsed = sqlTableExtractor.extract(m.sqlText());
                if (!parsed.parsed() || parsed.writes().isEmpty()) {
                    if (m.updateCount() > 0) {
                        log.warn("[WorkerReport] task={} statement 影响 {} 行但未解析出写表"
                                        + "（UPDATE/DELETE 或方言不支持），跳过 recordSynced",
                                taskDefId, m.updateCount());
                    }
                    continue;
                }
                for (String table : parsed.writes()) {
                    TableRef ref = lineageEdgeAssembler.tableRef(writeCoord, table);
                    lineageStore.recordSynced(tenantId, projectId, instanceId,
                            ref, m.updateCount(), null, bizDate, taskDefId);
                }
            }
        } catch (Exception e) {
            // 降级：neo4j 不可达/异常不阻断主链路（FR-005）
            log.warn("[WorkerReport] recordSynced 降级（不阻断）：instance={}, error={}",
                    instanceId, e.getMessage());
        }
    }

    /** 取任务写侧 datasource coord：target_datasource_id 优先，回退 datasource_id（与设计态同源）。 */
    private DatasourceCoord resolveWriteCoord(long tenantId, long projectId, Long taskDefId) {
        Long dsId = null;
        if (taskDefId != null) {
            try {
                dsId = jdbc.queryForObject(
                        "SELECT COALESCE(target_datasource_id, datasource_id) FROM task_def WHERE id=? AND deleted=0",
                        Long.class, taskDefId);
            } catch (Exception e) {
                // 查询失败/无行 → dsId 留 null，resolveCoord 走租户级降级身份
            }
        }
        return lineageEdgeAssembler.resolveCoord(tenantId, projectId, dsId);
    }

    /** worker 执行失败：有重试次数则回队重试，否则 → FAILED 并级联取消下游。 */
    public void reportFailed(UUID taskInstanceId, String failureReason, String tailLog) {
        TaskInstance ti = taskInstanceRepository.findById(taskInstanceId).orElse(null);
        if (ti == null) {
            return;
        }
        writeLog(taskInstanceId, null, tailLog);
        // 060（FR-009 / D2）：业务重试计数仅当"曾进入 RUNNING（started_at≠null）"才 +1。
        // infra 回收（租约过期/重启/下发失败）不经此路、不烧 business_attempt。
        if (ti.getStartedAt() != null) {
            stateMachine.incrementBusinessAttempt(taskInstanceId);
            ti.setBusinessAttempt((ti.getBusinessAttempt() == null ? 0 : ti.getBusinessAttempt()) + 1);
        }
        if (retryService.scheduleRetry(ti)) {
            log.info("[WorkerReport] task {} 失败重试（businessAttempt={}）", taskInstanceId, ti.getBusinessAttempt());
            wake();
            return;
        }

        // 终态推进 + 信号 append 同事务（FAILED 也是 WEAK 依赖放行终态，同样 no-loss）。
        // casTaskTerminalFromActive 不依赖外部 fromState 快照——WHERE state IN ('DISPATCHED','RUNNING')
        // 由 DB 裁决，闭合与 LeaseReaper 的 TOCTOU 竞态。
        txTemplate.executeWithoutResult(status -> {
            if (stateMachine.casTaskTerminalFromActive(taskInstanceId, InstanceStates.FAILED, failureReason)) {
                writeTerminalSignal(ti);
            }
        });

        recordTaskCompletion(taskInstanceId, "FAILED");
        recomputeWorkflow(ti.getWorkflowInstanceId());
        wake();
    }

    /** 重算工作流聚合态；若判定 FAILED 则级联取消下游；若 SUCCESS 则记录 SLA 基线。 */
    private void recomputeWorkflow(UUID workflowInstanceId) {
        if (workflowInstanceId == null) {
            return;
        }
        workflowStateService.computeAndUpdate(workflowInstanceId).ifPresent(state -> {
            if (InstanceStates.FAILED.equals(state)) {
                // 级联取消下游：先取受影响实例 id，批量置 STOPPED，再逐条补发事件供画布重染
                // （此处批量 UPDATE 绕过 InstanceStateMachine，故事件需在本方法补发）。
                List<UUID> toStop = jdbc.query(
                        "SELECT id FROM task_instance WHERE workflow_instance_id=? "
                                + "AND state IN ('WAITING','NOT_RUN','PAUSED') AND deleted=0",
                        (rs, n) -> {
                            Object o = rs.getObject(1);
                            return o == null ? null : (o instanceof UUID u ? u : UUID.fromString(o.toString()));
                        },
                        workflowInstanceId);
                jdbc.update(
                        "UPDATE task_instance SET state='STOPPED', finished_at=?, updated_at=? "
                                + "WHERE workflow_instance_id=? AND state IN ('WAITING','NOT_RUN','PAUSED') AND deleted=0",
                        LocalDateTime.now(), LocalDateTime.now(), workflowInstanceId);
                for (UUID id : toStop) {
                    if (id == null) continue;
                    eventBus.publish("dw:evt:" + workflowInstanceId,
                            "{\"taskId\":\"" + id + "\",\"taskState\":\"STOPPED\"}");
                }
            } else if (InstanceStates.SUCCESS.equals(state)) {
                slaService.recordCompletion(workflowInstanceId);
                // 043: publish WorkflowSucceededEvent for incident auto-heal (best-effort)
                try {
                    var row = jdbc.queryForMap(
                            "SELECT tenant_id, workflow_id FROM workflow_instance WHERE id = ?",
                            workflowInstanceId);
                    if (row != null && !row.isEmpty()) {
                        long tid = ((Number) row.get("TENANT_ID")).longValue();
                        Long wfId = (Long) row.get("WORKFLOW_ID");
                        eventPublisher.publishEvent(new WorkflowSucceededEvent(workflowInstanceId, wfId, tid));
                    }
                } catch (Exception e) {
                    // auto-heal signal is best-effort; do not fail workflow completion
                }
            }
        });
    }

    private void writeLog(UUID taskInstanceId, Integer exitCode, String tailLog) {
        jdbc.update("UPDATE task_instance SET log=?, exit_code=?, updated_at=? WHERE id=?",
                tailLog, exitCode, LocalDateTime.now(), taskInstanceId);
    }

    private void wake() {
        eventBus.publish(InstanceStates.WAKE_CHANNEL, "report");
    }

    /** 051: 写 TERMINAL 信号。由 reportFinished/reportFailed 在终态事务内调用；
     *  异常向上传播触发事务回滚（no-loss：完成与信号原子）。 */
    private void writeTerminalSignal(TaskInstance ti) {
        Long wfId = null;
        if (ti.getWorkflowInstanceId() != null) {
            var rows = jdbc.query(
                    "SELECT workflow_id FROM workflow_instance WHERE id = ?",
                    (rs, n) -> (Long) rs.getObject("workflow_id"),
                    ti.getWorkflowInstanceId());
            if (!rows.isEmpty()) wfId = rows.get(0);
        }
        readinessSignalWriter.writeTerminal(
                ti.getTenantId(), ti.getProjectId(), ti.getId(),
                wfId, ti.getWorkflowInstanceId(),
                ti.getWorkflowNodeId(), ti.getBizDate());
        // 不再 try-catch：INSERT 失败 → 异常传播 → 外层 txTemplate 回滚 casTaskTerminal → no-loss
    }

    /** 记录下发延迟（DISPATCHED→RUNNING 时间差）与任务执行计时启动。 */
    private void recordDeliveryLatency(UUID taskInstanceId) {
        try {
            java.util.List<java.time.LocalDateTime> rows = jdbc.query(
                    "SELECT updated_at FROM task_instance WHERE id=?",
                    (rs, n) -> rs.getTimestamp("updated_at") != null
                            ? rs.getTimestamp("updated_at").toLocalDateTime() : null,
                    taskInstanceId);
            if (!rows.isEmpty() && rows.get(0) != null) {
                java.time.Duration d = java.time.Duration.between(rows.get(0), java.time.LocalDateTime.now());
                metrics.recordDeliveryLatency(d);
            }
        } catch (Exception e) {
            // 指标静默吞错
        }
    }

    /** 记录任务完成（按终态 + task_def_id 维度）。 */
    private void recordTaskCompletion(UUID taskInstanceId, String outcome) {
        try {
            var rows = jdbc.query(
                    "SELECT ti.task_id, ti.started_at, ti.finished_at FROM task_instance ti WHERE ti.id=?",
                    (rs, n) -> new Object[]{
                            rs.getObject("task_id"),
                            rs.getTimestamp("started_at") != null ? rs.getTimestamp("started_at").toLocalDateTime() : null,
                            rs.getTimestamp("finished_at") != null ? rs.getTimestamp("finished_at").toLocalDateTime() : null
                    },
                    taskInstanceId);
            if (!rows.isEmpty()) {
                Object[] row = rows.get(0);
                Long taskId = row[0] != null ? ((Number) row[0]).longValue() : null;
                // 基于 started_at → finished_at 的耗时
                java.time.Duration d = java.time.Duration.ZERO;
                if (row[1] != null && row[2] != null) {
                    d = java.time.Duration.between((java.time.LocalDateTime) row[1], (java.time.LocalDateTime) row[2]);
                }
                metrics.recordTaskCompletion(d, outcome, taskId);
            }
        } catch (Exception e) {
            // 指标静默吞错
        }
    }
}
