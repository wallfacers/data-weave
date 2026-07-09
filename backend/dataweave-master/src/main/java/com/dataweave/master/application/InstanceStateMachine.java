package com.dataweave.master.application;

import com.dataweave.master.domain.EventBus;
import com.dataweave.master.domain.signal.AlertSignal;
import com.dataweave.master.quality.application.TaskSucceededEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 两级实例状态机的统一推进入口（design D3 死锁防御不变量）。
 *
 * <p>纪律：
 * <ol>
 *   <li><b>乐观 CAS</b>：所有状态推进走 {@code UPDATE … WHERE id=? AND state=?}，影响行数 0 即让步——
 *       无先读后写锁窗口，永不等待行锁，竞态由 DB 原子裁决（先到先得）。</li>
 *   <li><b>固定锁序</b>：跨两级更新一律先 task 后 workflow（本类方法各自单语句自治，调用方按此序组合）。</li>
 *   <li><b>事务内禁副作用</b>：本类只做状态落库，HTTP 下发等副作用由调用方在 CAS 成功（提交）之后执行。</li>
 * </ol>
 * 每个 CAS 方法返回是否成功（恰好 1 行受影响），调用方据此决定继续或让步。
 *
 * <p><b>状态事件补发</b>：每次 task CAS 成功后，向 {@code dw:evt:{workflowInstanceId}} 频道发布
 * {@code {"taskId":"<实例UUID>","taskState":"<新态>"}}，供画布 SSE（events/stream）实时给 DAG 节点
 * 变色并自动顶起运行中节点日志。单跑实例（workflow_instance_id 为 null）跳过。事件仅作 UI 辅助，
 * 发布失败/事务回滚导致的偶发误差由前端下次拉取自愈，故不影响状态机纪律。
 */
@Service
public class InstanceStateMachine {

    private final JdbcTemplate jdbc;
    private final EventBus eventBus;
    private final ApplicationEventPublisher eventPublisher;

    public InstanceStateMachine(JdbcTemplate jdbc, EventBus eventBus,
                                ApplicationEventPublisher eventPublisher) {
        this.jdbc = jdbc;
        this.eventBus = eventBus;
        this.eventPublisher = eventPublisher;
    }

    // ─── task_instance ───────────────────────────────────

    /** CAS 推进任务实例状态：{@code from → to}。成功（1 行）返回 true。 */
    public boolean casTaskState(UUID id, String from, String to) {
        int n = jdbc.update(
                "UPDATE task_instance SET state=?, updated_at=? WHERE id=? AND state=? AND deleted=0",
                to, LocalDateTime.now(), id, from);
        if (n == 1) publishTaskState(id, to);
        return n == 1;
    }

    /**
     * 写前置下发：CAS {@code WAITING → DISPATCHED} 并落 worker/租约/attempt（design D7 第一层）。
     * 成功后调用方才在事务外发起下发；下发失败再 CAS 回 WAITING。
     */
    public boolean casDispatch(UUID id, String from, String workerNodeCode,
                               LocalDateTime leaseExpireAt, int attempt) {
        int n = jdbc.update(
                "UPDATE task_instance SET state='DISPATCHED', worker_node_code=?, lease_expire_at=?, "
                        + "attempt=?, updated_at=? WHERE id=? AND state=? AND deleted=0",
                workerNodeCode, leaseExpireAt, attempt, LocalDateTime.now(), id, from);
        if (n == 1) publishTaskState(id, "DISPATCHED");
        return n == 1;
    }

    /**
     * 048 批量写前置下发:单 SQL {@code UPDATE FROM VALUES} 把一批 WAITING 实例 CAS 推到 DISPATCHED
     * 并落 worker/租约/attempt(design D7 第一层,batch 化)。{@code SELECT … FOR UPDATE SKIP LOCKED} 已在
     * 事务内锁住这些行 → state 必为 WAITING → 批量 UPDATE 必全成功,返回 updateCount(应 == placements.size(),
     * 不符仅由调用方 log 核对,理论不发生)。UPDATE FROM VALUES 无 RETURNING —— H2 兼容(见 research R1/R2:T1 FAIL,
     * T5 OK)。DISPATCHED 事件不在此发布——调用方收集 {@link DispatchedEvent} 于认领事务提交后
     * {@link #publishDispatchedEvents} 批量发(事务内逐条发布 = 每条 1 次回查 SELECT + 1 次 Redis RTT
     * 拉长持锁,且未提交先发、回滚成假事件)。
     */
    public int casDispatchBatch(List<DispatchPlacement> placements, LocalDateTime now) {
        if (placements.isEmpty()) return 0;
        int n = placements.size();
        StringBuilder sql = new StringBuilder(
                "UPDATE task_instance SET state='DISPATCHED', worker_node_code=v.nc, lease_expire_at=v.ls, "
                        + "attempt=v.at, updated_at=? FROM (VALUES ");
        for (int i = 0; i < n; i++) {
            if (i > 0) sql.append(',');
            sql.append("(CAST(? AS UUID),CAST(? AS VARCHAR),CAST(? AS TIMESTAMP),CAST(? AS INT))");
        }
        sql.append(") AS v(id,nc,ls,at) WHERE task_instance.id=v.id AND task_instance.state='WAITING' "
                + "AND task_instance.deleted=0");
        Object[] args = new Object[1 + n * 4];
        args[0] = now;
        int idx = 1;
        for (DispatchPlacement p : placements) {
            args[idx++] = p.id();
            args[idx++] = p.workerNodeCode();
            args[idx++] = p.leaseExpireAt();
            args[idx++] = p.attempt();
        }
        int updateCount = jdbc.update(sql.toString(), args);
        return updateCount;
    }

    /** 048:批量下发的单个 placement(id + worker + 租约 + attempt)。 */
    public record DispatchPlacement(UUID id, String workerNodeCode, LocalDateTime leaseExpireAt, int attempt) {}

    /** 收尾:待发布的 DISPATCHED 事件(workflowInstanceId 由认领 SQL 带回,发布时免逐条回查)。 */
    public record DispatchedEvent(UUID taskInstanceId, UUID workflowInstanceId) {}

    /**
     * 收尾:认领事务提交后批量发布 DISPATCHED 事件(语义同 {@link #publishTaskState},但 wfId 已知不回查)。
     * 单跑实例(wfId=null)无订阅通道,跳过——与 publishTaskState 一致。
     */
    public void publishDispatchedEvents(List<DispatchedEvent> events) {
        for (DispatchedEvent e : events) {
            if (e.workflowInstanceId() == null) continue;
            try {
                eventBus.publish("dw:evt:" + e.workflowInstanceId(),
                        "{\"taskId\":\"" + e.taskInstanceId() + "\",\"taskState\":\"DISPATCHED\"}");
            } catch (Exception ex) {
                // 事件仅作 UI 辅助，发布失败不影响状态推进。
            }
        }
    }

    /**
     * 收尾:滞留下发守卫——实例仍处 DISPATCHED 且 attempt 匹配才允许发出。租约自 claim 时刻起算,
     * 命令在 dispatch 队列滞留超租约会被 LeaseReaper 回收重派(attempt+1),旧命令若照发则同实例双跑
     * (worker 幂等键含 attempt 拦不住)。守卫后残余竞态窗口收窄到毫秒级(彻底闭合需 worker 侧 fencing)。
     */
    public boolean isCurrentDispatch(UUID id, int attempt) {
        // 正向判定陈旧:只有当 DB 中 attempt 已被推进到严格更高(说明本命令对应的派单已被 LeaseReaper
        // 回收重派)才判定陈旧、拒发;读到相等/更低即当前派单,照发。行不存在(已删)→拒发,不下发幽灵。
        //
        // 关键——不再要求 state='DISPATCHED':046 fire-and-forget 下发在独立线程/连接上执行,守卫可能抢在
        // 认领事务提交对本连接可见之前读库(读到 pre-claim 的 WAITING/低 attempt 快照);原实现 WHERE
        // state='DISPATCHED' 此时读空即误判陈旧、丢掉唯一一次合法下发,直到 120s 租约被 LeaseReaper 兜底
        // 重投——表现为每次调度延迟约 2 分钟。放宽后残留的「回收窗口」双跑由 reportStarted 的
        // DISPATCHED→RUNNING CAS + InProcessTaskExecutionGateway 的 fencing 收口(worker 侧幂等键含 attempt)。
        Integer current = jdbc.query(
                "SELECT COALESCE(attempt, 0) FROM task_instance WHERE id=? AND deleted=0",
                rs -> rs.next() ? (Integer) rs.getObject(1) : null, id);
        return current != null && current <= attempt;
    }

    /** CAS 置终态并记结束时间/失败归因（to ∈ SUCCESS/FAILED/STOPPED）。 */
    public boolean casTaskTerminal(UUID id, String from, String to, String failureReason) {
        int n = jdbc.update(
                "UPDATE task_instance SET state=?, failure_reason=?, finished_at=?, updated_at=? "
                        + "WHERE id=? AND state=? AND deleted=0",
                to, failureReason, LocalDateTime.now(), LocalDateTime.now(), id, from);
        if (n == 1) {
            // F1 收口（audit §3-F1 残留副作用）：终态副作用（UI 事件 / FAILED alert / SUCCESS 质量门禁）
            // 挪到事务提交后发。此 CAS 现被 WorkerReportService 与 writeTerminalSignal 包在同一事务里，
            // 若信号 INSERT 失败回滚，同步发出的 alert/质量事件会成"假事件"（task 实际未到终态）。
            // afterCommit 保证仅在真提交后发；无活动事务（auto-commit 调用方）则立即发——语义等价旧行为。
            runAfterCommitOrNow(() -> {
                publishTaskState(id, to);
                if ("FAILED".equals(to)) {
                    // 021-alert: 终态 FAILED → 发 AlertSignal
                    publishAlertSignalForTask(id, failureReason);
                } else if ("SUCCESS".equals(to)) {
                    // 022-data-quality: post-task 门禁钩子（D2.1）—— 任务 SUCCESS 后触发质量断言
                    try {
                        Long taskId = jdbc.queryForObject(
                                "SELECT task_id FROM task_instance WHERE id = ?", Long.class, id);
                        Long tenantId = jdbc.queryForObject(
                                "SELECT tenant_id FROM task_instance WHERE id = ?", Long.class, id);
                        if (taskId != null && tenantId != null) {
                            eventPublisher.publishEvent(new TaskSucceededEvent(id, taskId, tenantId));
                        }
                    } catch (Exception e) {
                        // 事件仅作门禁辅助，发布失败不影响状态推进（同 publishTaskState 纪律）
                    }
                }
            });
        }
        return n == 1;
    }

    /**
     * F1 收口：有活动事务时把副作用注册到 {@code afterCommit}（仅真提交后触发，回滚则不发→无假事件）；
     * 无活动事务（auto-commit 调用方）则立即执行——与并入事务前的旧行为等价。
     */
    private void runAfterCommitOrNow(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    /** 软抢占：CAS {@code RUNNING/DISPATCHED → PREEMPTED}（不耗 attempt）。 */
    public boolean casPreempt(UUID id, String from) {
        int n = jdbc.update(
                "UPDATE task_instance SET state='PREEMPTED', failure_reason='PREEMPTED', updated_at=? "
                        + "WHERE id=? AND state=? AND deleted=0",
                LocalDateTime.now(), id, from);
        if (n == 1) publishTaskState(id, "PREEMPTED");
        return n == 1;
    }

    /** 回炉：CAS {@code PREEMPTED → WAITING}，清空 worker/租约（attempt 不变）。 */
    public boolean casRequeue(UUID id, String from) {
        int n = jdbc.update(
                "UPDATE task_instance SET state='WAITING', worker_node_code=NULL, lease_expire_at=NULL, "
                        + "failure_reason=NULL, updated_at=? WHERE id=? AND state=? AND deleted=0",
                LocalDateTime.now(), id, from);
        if (n == 1) publishTaskState(id, "WAITING");
        return n == 1;
    }

    /** 续租：心跳到达时延长租约（仅运行中实例）。 */
    public boolean renewLease(UUID id, LocalDateTime leaseExpireAt) {
        int n = jdbc.update(
                "UPDATE task_instance SET lease_expire_at=?, updated_at=? "
                        + "WHERE id=? AND state IN ('DISPATCHED','RUNNING') AND deleted=0",
                leaseExpireAt, LocalDateTime.now(), id);
        return n == 1;
    }

    // ─── workflow_instance ───────────────────────────────

    /** CAS 推进工作流实例状态：{@code from → to}（固定锁序：在 task 之后调用）。 */
    public boolean casWorkflowState(UUID id, String from, String to) {
        int n = jdbc.update(
                "UPDATE workflow_instance SET state=?, updated_at=? WHERE id=? AND state=? AND deleted=0",
                to, LocalDateTime.now(), id, from);
        if (n == 1) {
            publishWorkflowState(id, to);
            if ("FAILED".equals(to) || "STOPPED".equals(to)) {
                publishAlertSignalForWorkflow(id, to);
            }
        }
        return n == 1;
    }

    // ─── 状态事件补发 ─────────────────────────────────────

    /** 任务实例状态变迁 → 发布到其所属工作流实例的事件频道（单跑实例 workflow_instance_id 为 null 时跳过）。 */
    private void publishTaskState(UUID taskInstanceId, String state) {
        try {
            UUID workflowInstanceId = jdbc.query(
                    "SELECT workflow_instance_id FROM task_instance WHERE id=?",
                    rs -> {
                        if (!rs.next()) return null;
                        Object o = rs.getObject(1);
                        if (o == null) return null;
                        return (o instanceof UUID u) ? u : UUID.fromString(o.toString());
                    },
                    taskInstanceId);
            if (workflowInstanceId == null) return;
            eventBus.publish("dw:evt:" + workflowInstanceId,
                    "{\"taskId\":\"" + taskInstanceId + "\",\"taskState\":\"" + state + "\"}");
        } catch (Exception e) {
            // 事件仅作 UI 辅助，发布失败不影响状态推进。
        }
    }

    /** 工作流实例整体状态变迁 → 发布到自身事件频道（供实例详情视图叠加整体态）。 */
    private void publishWorkflowState(UUID workflowInstanceId, String state) {
        try {
            eventBus.publish("dw:evt:" + workflowInstanceId,
                    "{\"workflowState\":\"" + state + "\"}");
        } catch (Exception e) {
            // 同上：事件仅作 UI 辅助。
        }
    }

    // ─── 告警信号发布（021 alert-engine）─────────────────────

    /** 任务实例终态 → 发布 AlertSignal（TASK_FAILED / TASK_TIMEOUT）。 */
    private void publishAlertSignalForTask(UUID taskInstanceId, String failureReason) {
        try {
            var row = jdbc.queryForMap(
                    "SELECT ti.tenant_id, ti.task_id, ti.workflow_instance_id, td.name AS task_name " +
                    "FROM task_instance ti LEFT JOIN task_def td ON td.id = ti.task_id WHERE ti.id=?",
                    taskInstanceId);
            if (row.isEmpty()) return;
            long tenantId = ((Number) row.get("TENANT_ID")).longValue();
            Long taskId = (Long) row.get("TASK_ID");
            String taskName = (String) row.get("TASK_NAME");
            Object wiIdRaw = row.get("WORKFLOW_INSTANCE_ID");
            String wiId = wiIdRaw != null ? wiIdRaw.toString() : null;

            AlertSignal.Type type = "TIMEOUT".equalsIgnoreCase(failureReason)
                    ? AlertSignal.Type.TASK_TIMEOUT : AlertSignal.Type.TASK_FAILED;
            Map<String, Object> ctx = new LinkedHashMap<>();
            ctx.put("taskInstanceId", taskInstanceId.toString());
            ctx.put("taskId", taskId);
            ctx.put("taskName", taskName);
            ctx.put("workflowInstanceId", wiId);
            ctx.put("failureReason", failureReason);

            eventPublisher.publishEvent(new AlertSignal(type, tenantId,
                    taskId != null ? taskId.toString() : taskInstanceId.toString(),
                    "HIGH", ctx));
        } catch (Exception e) {
            // 告警信号仅作辅助，发布失败不影响状态推进。
        }
    }

    /** 工作流实例终态 → 发布 AlertSignal（WORKFLOW_STATE）。 */
    private void publishAlertSignalForWorkflow(UUID workflowInstanceId, String state) {
        try {
            var row = jdbc.queryForMap(
                    "SELECT wi.tenant_id, wi.workflow_id, wd.name AS workflow_name " +
                    "FROM workflow_instance wi LEFT JOIN workflow_def wd ON wd.id = wi.workflow_id WHERE wi.id=?",
                    workflowInstanceId);
            if (row.isEmpty()) return;
            long tenantId = ((Number) row.get("TENANT_ID")).longValue();
            Long workflowId = (Long) row.get("WORKFLOW_ID");
            String workflowName = (String) row.get("WORKFLOW_NAME");

            Map<String, Object> ctx = new LinkedHashMap<>();
            ctx.put("workflowInstanceId", workflowInstanceId.toString());
            ctx.put("workflowId", workflowId);
            ctx.put("workflowName", workflowName);
            ctx.put("state", state);

            String severity = "STOPPED".equals(state) ? "MEDIUM" : "HIGH";
            eventPublisher.publishEvent(new AlertSignal(AlertSignal.Type.WORKFLOW_STATE, tenantId,
                    workflowId != null ? workflowId.toString() : workflowInstanceId.toString(),
                    severity, ctx));
        } catch (Exception e) {
            // 告警信号仅作辅助，发布失败不影响状态推进。
        }
    }
}
