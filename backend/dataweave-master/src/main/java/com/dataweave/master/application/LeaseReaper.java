package com.dataweave.master.application;

import com.dataweave.master.domain.EventBus;
import com.dataweave.master.domain.InstanceStates;
import com.dataweave.master.domain.WorkerNode;
import com.dataweave.master.domain.WorkerNodeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 租约回收器（task 3.6 / design D7 第三层 / 060 infra 回收分类）：定时扫描过期的 DISPATCHED/RUNNING 实例。
 *
 * <p>两种失效模式（均归 infra，060 不烧 business_attempt、infra_count++、超限→SUSPENDED）：
 * <ul>
 *   <li><b>WORKER_LOST</b>：租约过期且节点离线（心跳超时）—— 计入节点熔断（节点死了，FR-003）。</li>
 *   <li><b>WORKER_RESTART</b>：节点在线但 incarnation 已变化 —— 由 FleetService 节点级即时回收承担主路径，
 *       此处为兜底扫描；不计熔断（正常重启由稳定窗处置，D4）。</li>
 * </ul>
 *
 * <p>060 回收动作：{@link InstanceStateMachine#reclaimInfra}（active→WAITING + infra_count++，不动 business_attempt，
 * 超限→SUSPENDED）；不再走 RetryService/casTaskTerminal（永不因 infra 判终态 FAILED，FR-008）。
 */
@Service
public class LeaseReaper {

    private static final Logger log = LoggerFactory.getLogger(LeaseReaper.class);

    private final JdbcTemplate jdbc;
    private final InstanceStateMachine stateMachine;
    private final NodeHealthService nodeHealthService;
    private final WorkerNodeRepository nodeRepository;
    private final EventBus eventBus;
    private final SchedulerMetrics metrics;
    private final WorkflowStateService workflowStateService;
    private final int infraRedispatchMax;

    public LeaseReaper(JdbcTemplate jdbc,
                       InstanceStateMachine stateMachine,
                       NodeHealthService nodeHealthService,
                       WorkerNodeRepository nodeRepository,
                       EventBus eventBus,
                       SchedulerMetrics metrics,
                       WorkflowStateService workflowStateService,
                       @Value("${scheduler.infra-redispatch-max:10}") int infraRedispatchMax) {
        this.jdbc = jdbc;
        this.stateMachine = stateMachine;
        this.nodeHealthService = nodeHealthService;
        this.nodeRepository = nodeRepository;
        this.eventBus = eventBus;
        this.metrics = metrics;
        this.workflowStateService = workflowStateService;
        this.infraRedispatchMax = infraRedispatchMax;
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
     * 060 infra 回收：{@link InstanceStateMachine#reclaimInfra}（active→WAITING + infra_count++，不动 business_attempt，
     * 超 infraRedispatchMax → SUSPENDED）。永不判终态 FAILED（FR-008）。
     *
     * <p>节点级熔断：WORKER_LOST（节点死了）→ {@code recordInfraFailure} 计入（FR-003）；
     * WORKER_RESTART（正常重启）不计——由稳定窗处置（D4），避免滚动重启节点被误隔离。
     *
     * @return true 表示本调用赢得回收（reclaimInfra 单赢）
     */
    private boolean failWithRetry(ExpiredInstance inst, String reason) {
        boolean won = stateMachine.reclaimInfra(inst.id, infraRedispatchMax);
        if (!won) {
            return false;  // 竞态：他方已回收/回报推进
        }
        log.info("[LeaseReaper] 实例 {} 从 {} infra 回收（{}）", inst.id, inst.state, reason);

        // 节点级熔断：仅 WORKER_LOST 计入（WORKER_RESTART 正常重启不计，D4）
        if ("WORKER_LOST".equals(reason) && inst.workerNodeCode != null) {
            nodeHealthService.recordInfraFailure(inst.workerNodeCode);
        }
        // 重算父流聚合态：infra 回收不属于 WorkerReportService 回报路径，没有其他事件会顺带重算。
        if (inst.workflowInstanceId != null) {
            workflowStateService.computeAndUpdate(inst.workflowInstanceId);
        }
        metrics.markLeaseReclaim();  // 仅在真实回收（reclaimInfra 赢）后计数一次
        return true;
    }

    private static final class ExpiredInstance {
        UUID id;
        String state;
        String workerNodeCode;
        int attempt;
        UUID workflowInstanceId;
    }

}
