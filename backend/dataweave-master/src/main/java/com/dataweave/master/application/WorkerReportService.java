package com.dataweave.master.application;

import com.dataweave.master.domain.EventBus;
import com.dataweave.master.domain.InstanceStates;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.TaskInstanceRepository;
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

    public WorkerReportService(InstanceStateMachine stateMachine,
                               TaskInstanceRepository taskInstanceRepository,
                               WorkflowStateService workflowStateService,
                               RetryService retryService,
                               SchedulerMetrics metrics,
                               SlaService slaService,
                               EventBus eventBus,
                               JdbcTemplate jdbc) {
        this.stateMachine = stateMachine;
        this.taskInstanceRepository = taskInstanceRepository;
        this.workflowStateService = workflowStateService;
        this.retryService = retryService;
        this.metrics = metrics;
        this.slaService = slaService;
        this.eventBus = eventBus;
        this.jdbc = jdbc;
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
    public void reportFinished(UUID taskInstanceId, Integer exitCode, String tailLog) {
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
        recomputeWorkflow(ti.getWorkflowInstanceId());
        wake();
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
