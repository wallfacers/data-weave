package com.dataweave.master.application;

import com.dataweave.master.domain.EventBus;
import com.dataweave.master.domain.signal.AlertSignal;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
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

    /** CAS 置终态并记结束时间/失败归因（to ∈ SUCCESS/FAILED/STOPPED）。 */
    public boolean casTaskTerminal(UUID id, String from, String to, String failureReason) {
        int n = jdbc.update(
                "UPDATE task_instance SET state=?, failure_reason=?, finished_at=?, updated_at=? "
                        + "WHERE id=? AND state=? AND deleted=0",
                to, failureReason, LocalDateTime.now(), LocalDateTime.now(), id, from);
        if (n == 1) {
            publishTaskState(id, to);
            if ("FAILED".equals(to)) {
                publishAlertSignalForTask(id, failureReason);
            }
        }
        return n == 1;
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
                    "SELECT tenant_id, task_id, workflow_instance_id FROM task_instance WHERE id=?",
                    taskInstanceId);
            if (row.isEmpty()) return;
            long tenantId = ((Number) row.get("TENANT_ID")).longValue();
            Long taskId = (Long) row.get("TASK_ID");
            Object wiIdRaw = row.get("WORKFLOW_INSTANCE_ID");
            String wiId = wiIdRaw != null ? wiIdRaw.toString() : null;

            AlertSignal.Type type = "TIMEOUT".equalsIgnoreCase(failureReason)
                    ? AlertSignal.Type.TASK_TIMEOUT : AlertSignal.Type.TASK_FAILED;
            Map<String, Object> ctx = new LinkedHashMap<>();
            ctx.put("taskInstanceId", taskInstanceId.toString());
            ctx.put("taskId", taskId);
            ctx.put("workflowInstanceId", wiId);
            ctx.put("failureReason", failureReason);

            eventPublisher.publishEvent(new AlertSignal(type, tenantId,
                    taskId != null ? taskId.toString() : taskInstanceId.toString(),
                    null, ctx));
        } catch (Exception e) {
            // 告警信号仅作辅助，发布失败不影响状态推进。
        }
    }

    /** 工作流实例终态 → 发布 AlertSignal（WORKFLOW_STATE）。 */
    private void publishAlertSignalForWorkflow(UUID workflowInstanceId, String state) {
        try {
            var row = jdbc.queryForMap(
                    "SELECT tenant_id, workflow_id FROM workflow_instance WHERE id=?",
                    workflowInstanceId);
            if (row.isEmpty()) return;
            long tenantId = ((Number) row.get("TENANT_ID")).longValue();
            Long workflowId = (Long) row.get("WORKFLOW_ID");

            Map<String, Object> ctx = new LinkedHashMap<>();
            ctx.put("workflowInstanceId", workflowInstanceId.toString());
            ctx.put("workflowId", workflowId);
            ctx.put("state", state);

            eventPublisher.publishEvent(new AlertSignal(AlertSignal.Type.WORKFLOW_STATE, tenantId,
                    workflowId != null ? workflowId.toString() : workflowInstanceId.toString(),
                    null, ctx));
        } catch (Exception e) {
            // 告警信号仅作辅助，发布失败不影响状态推进。
        }
    }
}
