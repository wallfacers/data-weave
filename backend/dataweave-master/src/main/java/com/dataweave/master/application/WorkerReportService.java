package com.dataweave.master.application;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

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
    private final JdbcTemplate jdbc;
    private final LineageStore lineageStore;
    private final SqlTableExtractor sqlTableExtractor;
    private final LineageEdgeAssembler lineageEdgeAssembler;

    public WorkerReportService(InstanceStateMachine stateMachine,
                               TaskInstanceRepository taskInstanceRepository,
                               WorkflowStateService workflowStateService,
                               RetryService retryService,
                               SchedulerMetrics metrics,
                               SlaService slaService,
                               EventBus eventBus,
                               JdbcTemplate jdbc,
                               LineageStore lineageStore,
                               SqlTableExtractor sqlTableExtractor,
                               LineageEdgeAssembler lineageEdgeAssembler) {
        this.stateMachine = stateMachine;
        this.taskInstanceRepository = taskInstanceRepository;
        this.workflowStateService = workflowStateService;
        this.retryService = retryService;
        this.metrics = metrics;
        this.slaService = slaService;
        this.eventBus = eventBus;
        this.jdbc = jdbc;
        this.lineageStore = lineageStore;
        this.sqlTableExtractor = sqlTableExtractor;
        this.lineageEdgeAssembler = lineageEdgeAssembler;
    }

    /** worker 开始执行：DISPATCHED → RUNNING。 */
    public void reportStarted(UUID taskInstanceId) {
        if (stateMachine.casTaskState(taskInstanceId, InstanceStates.DISPATCHED, InstanceStates.RUNNING)) {
            jdbc.update("UPDATE task_instance SET started_at=? WHERE id=? AND started_at IS NULL",
                    LocalDateTime.now(), taskInstanceId);
            // 记录下发延迟（DISPATCHED → RUNNING）
            recordDeliveryLatency(taskInstanceId);
        }
        wake();
    }

    /** worker 执行成功：→ SUCCESS，回写日志/退出码，重算工作流聚合态。 */
    public void reportFinished(UUID taskInstanceId, Integer exitCode, String tailLog,
                               List<StatementMetric> statementMetrics) {
        TaskInstance ti = taskInstanceRepository.findById(taskInstanceId).orElse(null);
        if (ti == null) {
            return;
        }
        boolean ok = stateMachine.casTaskTerminal(taskInstanceId, ti.getState(), InstanceStates.SUCCESS, null);
        if (!ok) {
            // 竞态（如已被抢占/终止）：让步。
            wake();
            return;
        }
        writeLog(taskInstanceId, exitCode, tailLog);
        recordTaskCompletion(taskInstanceId, "SUCCESS");
        recordSyncedRows(ti, statementMetrics);   // feature 025: 运行态同步行数（降级零阻断，仅 SUCCESS）
        recomputeWorkflow(ti.getWorkflowInstanceId());
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
        if (retryService.scheduleRetry(ti)) {
            log.info("[WorkerReport] task {} 失败重试（attempt={}）", taskInstanceId, ti.getAttempt());
            wake();
            return;
        }
        stateMachine.casTaskTerminal(taskInstanceId, ti.getState(), InstanceStates.FAILED, failureReason);
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
