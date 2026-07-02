package com.dataweave.master.application;

import com.dataweave.master.domain.EventBus;
import com.dataweave.master.domain.InstanceStates;
import com.dataweave.master.domain.WorkerNode;
import com.dataweave.master.domain.WorkerNodeRepository;
import com.dataweave.master.domain.signal.AlertSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 租约回收器（task 3.6 / design D7 第三层）：定时扫描过期的 DISPATCHED/RUNNING 实例。
 *
 * <p>两种失效模式：
 * <ul>
 *   <li><b>WORKER_LOST</b>：租约过期且节点离线（心跳超时）</li>
 *   <li><b>WORKER_RESTART</b>：节点在线但 incarnation 已变化（进程重启后旧实例全部失效）</li>
 * </ul>
 *
 * <p>回收动作：CAS 置 FAILED + failure_reason，触发重试（RetryService）或终态。
 * 扫描以 CAS 守卫，竞态安全（如 worker 恰好在此时回报成功）。
 */
@Service
public class LeaseReaper {

    private static final Logger log = LoggerFactory.getLogger(LeaseReaper.class);

    private final JdbcTemplate jdbc;
    private final InstanceStateMachine stateMachine;
    private final RetryService retryService;
    private final WorkerNodeRepository nodeRepository;
    private final EventBus eventBus;
    private final SchedulerMetrics metrics;
    private final ApplicationEventPublisher eventPublisher;
    private final WorkflowStateService workflowStateService;

    public LeaseReaper(JdbcTemplate jdbc,
                       InstanceStateMachine stateMachine,
                       RetryService retryService,
                       WorkerNodeRepository nodeRepository,
                       EventBus eventBus,
                       SchedulerMetrics metrics,
                       ApplicationEventPublisher eventPublisher,
                       WorkflowStateService workflowStateService) {
        this.jdbc = jdbc;
        this.stateMachine = stateMachine;
        this.retryService = retryService;
        this.nodeRepository = nodeRepository;
        this.eventBus = eventBus;
        this.metrics = metrics;
        this.eventPublisher = eventPublisher;
        this.workflowStateService = workflowStateService;
    }

    /**
     * 定期扫描租约过期实例（默认 15s），配合兜底轮询周期。
     * 只处理 lease_expire_at < now 的 DISPATCHED/RUNNING 实例。
     */
    @Scheduled(fixedDelayString = "${scheduler.reaper.interval-ms:15000}", initialDelayString = "${scheduler.reaper.initial-ms:30000}")
    public void reap() {
        LocalDateTime now = LocalDateTime.now();
        int reaped = reapLostInstances(now);
        reaped += reapRestartedInstances(now);
        if (reaped > 0) {
            log.info("[LeaseReaper] 回收 {} 个过期实例", reaped);
            eventBus.publish(InstanceStates.WAKE_CHANNEL, "reap");
        }
    }

    /**
     * 扫描租约过期且节点离线的实例 → FAILED（WORKER_LOST）。
     */
    private int reapLostInstances(LocalDateTime now) {
        // 查找 lease_expire_at 已过期 且 节点 OFFLINE 的 DISPATCHED/RUNNING 实例
        List<ExpiredInstance> expired = jdbc.query(
                "SELECT ti.id, ti.state, ti.worker_node_code, ti.attempt, ti.workflow_instance_id " +
                        "FROM task_instance ti " +
                        "JOIN worker_nodes wn ON ti.worker_node_code = wn.node_code " +
                        "WHERE ti.state IN ('DISPATCHED','RUNNING') " +
                        "AND ti.lease_expire_at < ? " +
                        "AND ti.worker_node_code IS NOT NULL " +
                        "AND wn.status = 'OFFLINE' " +
                        "AND ti.deleted = 0 " +
                        "LIMIT 100",
                (rs, n) -> {
                    ExpiredInstance inst = new ExpiredInstance();
                    inst.id = rs.getObject("id", UUID.class);
                    inst.state = rs.getString("state");
                    inst.workerNodeCode = rs.getString("worker_node_code");
                    inst.attempt = rs.getInt("attempt");
                    inst.workflowInstanceId = rs.getObject("workflow_instance_id", UUID.class);
                    return inst;
                },
                now);

        int reaped = 0;
        for (ExpiredInstance inst : expired) {
            if (failWithRetry(inst, "WORKER_LOST")) {
                reaped++;
            }
        }
        return reaped;
    }

    /**
     * 扫描节点在线但 incarnation 已变的实例 → FAILED（WORKER_RESTART）。
     * FleetService.report() 已检测 incarnation 变化，此方法作为兜底。
     */
    private int reapRestartedInstances(LocalDateTime now) {
        // 查找节点 ONLINE 但 incarnation 不匹配的实例
        // 简化实现：查找 lease_expire_at 已过期的 DISPATCHED/RUNNING 且节点 ONLINE 的实例
        // 如果节点 ONLINE 但租约过期，说明可能 incarnation 已变但 worker 尚未上报运行中实例
        List<ExpiredInstance> expired = jdbc.query(
                "SELECT ti.id, ti.state, ti.worker_node_code, ti.attempt, ti.workflow_instance_id " +
                        "FROM task_instance ti " +
                        "JOIN worker_nodes wn ON ti.worker_node_code = wn.node_code " +
                        "WHERE ti.state IN ('DISPATCHED','RUNNING') " +
                        "AND ti.lease_expire_at < ? " +
                        "AND ti.worker_node_code IS NOT NULL " +
                        "AND wn.status = 'ONLINE' " +
                        "AND ti.deleted = 0 " +
                        "LIMIT 100",
                (rs, n) -> {
                    ExpiredInstance inst = new ExpiredInstance();
                    inst.id = rs.getObject("id", UUID.class);
                    inst.state = rs.getString("state");
                    inst.workerNodeCode = rs.getString("worker_node_code");
                    inst.attempt = rs.getInt("attempt");
                    inst.workflowInstanceId = rs.getObject("workflow_instance_id", UUID.class);
                    return inst;
                },
                now);

        int reaped = 0;
        for (ExpiredInstance inst : expired) {
            // ONLINE 节点但租约过期 → 可能 incarnation 已变
            if (failWithRetry(inst, "WORKER_RESTART")) {
                reaped++;
            }
        }
        return reaped;
    }

    /**
     * CAS 置 FAILED + failure_reason，若仍有重试次数则回队 WAITING。
     *
     * @return true 表示成功回收
     */
    private boolean failWithRetry(ExpiredInstance inst, String reason) {
        // 先标记 FAILED
        boolean casOk = stateMachine.casTaskTerminal(inst.id, inst.state, InstanceStates.FAILED, reason);
        if (!casOk) {
            return false; // 竞态：其他 master 或回报已推进状态
        }
        log.info("[LeaseReaper] 实例 {} 从 {} 回收（{}）", inst.id, inst.state, reason);

        // 尝试重试（如果仍有次数）
        tryRetry(inst, reason);
        // 重算父流聚合态：无论最终是重试回队 WAITING 还是耗尽留 FAILED，都可能是该流最后一个未完成节点，
        // 且租约回收不属于 WorkerReportService 回报路径，没有其他事件会顺带重算。
        if (inst.workflowInstanceId != null) {
            workflowStateService.computeAndUpdate(inst.workflowInstanceId);
        }
        metrics.markLeaseReclaim(); // 仅在 casTaskTerminal 成功（真实回收）后计数一次
        // 021 alert-engine: publish NODE_OFFLINE for heartbeat timeout
        if ("WORKER_LOST".equals(reason)) {
            publishNodeOffline(inst);
        }
        return true;
    }

    private void tryRetry(ExpiredInstance inst, String reason) {
        // 查重试次数
        Integer retryMax = jdbc.query(
                "SELECT td.retry_max FROM task_def td " +
                        "JOIN task_instance ti ON ti.task_id = td.id " +
                        "WHERE ti.id = ?",
                (rs, n) -> rs.getObject("retry_max", Integer.class),
                inst.id).stream().findFirst().orElse(0);

        int attempt = inst.attempt;
        if (retryMax != null && attempt <= retryMax) {
            // 仍有重试次数，CAS 回 WAITING
            stateMachine.casRequeue(inst.id, InstanceStates.FAILED);
            log.info("[LeaseReaper] 实例 {} 重试回队（attempt={}, retryMax={}）",
                    inst.id, attempt, retryMax);
        }
    }

    private static final class ExpiredInstance {
        UUID id;
        String state;
        String workerNodeCode;
        int attempt;
        UUID workflowInstanceId;
    }

    // ─── 告警信号发布（021 alert-engine）─────────────────────

    private void publishNodeOffline(ExpiredInstance inst) {
        try {
            var row = jdbc.queryForMap(
                    "SELECT tenant_id, task_id FROM task_instance WHERE id = ?", inst.id);
            if (row.isEmpty()) return;
            long tenantId = ((Number) row.get("TENANT_ID")).longValue();
            Map<String, Object> ctx = new LinkedHashMap<>();
            ctx.put("taskInstanceId", inst.id.toString());
            ctx.put("workerNodeCode", inst.workerNodeCode);
            ctx.put("failureReason", "WORKER_LOST");
            eventPublisher.publishEvent(new AlertSignal(AlertSignal.Type.NODE_OFFLINE, tenantId,
                    inst.workerNodeCode, null, ctx));
        } catch (Exception e) {
            // 告警信号仅作辅助，发布失败不影响回收。
        }
    }
}
