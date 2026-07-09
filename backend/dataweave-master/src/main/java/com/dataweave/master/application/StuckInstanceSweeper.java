package com.dataweave.master.application;

import com.dataweave.master.domain.EventBus;
import com.dataweave.master.domain.InstanceStates;
import com.dataweave.master.domain.WorkerNode;
import com.dataweave.master.domain.WorkerNodeRepository;
import com.dataweave.master.domain.signal.AlertSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 060 卡住实例巡检器（FR-012/014/015）：周期兜底抽干 + 卡住告警（仅可见性，永不自动判终态）。
 *
 * <ul>
 *   <li><b>兜底抽干</b>（FR-014）：有 WAITING 实例且有可用节点时发 WAKE——覆盖 FleetService 事件唤醒漏掉的时间触发恢复
 *       （隔离到期、incarnation 跨过稳定窗）。配合 SchedulerKernel 5s 轮询，保证恢复后无"就绪未认领"残留（SC-003）。</li>
 *   <li><b>无节点等待告警</b>（FR-015）：就绪任务因无任何可用节点滞留超 {@code stuck-wait-alert-ms} → AlertSignal(NODE_STARVATION)，
 *       <b>不</b>自动判死。</li>
 *   <li><b>SUSPENDED 巡检</b>（FR-012）：巡检保护挂起实例，续发 TASK_SUSPENDED 告警交人工。</li>
 * </ul>
 *
 * <p>告警失败不影响主流程（best-effort）。
 */
@Service
public class StuckInstanceSweeper {

    private static final Logger log = LoggerFactory.getLogger(StuckInstanceSweeper.class);

    private final JdbcTemplate jdbc;
    private final WorkerNodeRepository nodeRepository;
    private final EventBus eventBus;
    private final ApplicationEventPublisher eventPublisher;
    private final long stabilizationWindowMs;
    private final long stuckWaitAlertMs;

    public StuckInstanceSweeper(JdbcTemplate jdbc, WorkerNodeRepository nodeRepository, EventBus eventBus,
                                ApplicationEventPublisher eventPublisher,
                                @Value("${scheduler.node.stabilization-window-ms:15000}") long stabilizationWindowMs,
                                @Value("${scheduler.stuck-wait-alert-ms:300000}") long stuckWaitAlertMs) {
        this.jdbc = jdbc;
        this.nodeRepository = nodeRepository;
        this.eventBus = eventBus;
        this.eventPublisher = eventPublisher;
        this.stabilizationWindowMs = stabilizationWindowMs;
        this.stuckWaitAlertMs = stuckWaitAlertMs;
    }

    @Scheduled(fixedDelayString = "${scheduler.stuck-sweep-ms:30000}",
               initialDelayString = "${scheduler.stuck-sweep-initial-ms:60000}")
    public void sweep() {
        try {
            LocalDateTime now = LocalDateTime.now();
            boolean hasAvailableNode = !availableNodes(now).isEmpty();

            wakeIfWaitingAndAvailable(hasAvailableNode);   // FR-014 兜底抽干
            alertNodeStarvation(now, hasAvailableNode);    // FR-015 无节点等待告警
            alertSuspended();                              // FR-012 SUSPENDED 巡检
        } catch (Exception e) {
            log.warn("[StuckInstanceSweeper] 巡检异常（不影响调度）：{}", e.getMessage());
        }
    }

    /** 当前可用节点（心跳新鲜 + 纪元过稳定窗 + 未隔离，复用 SlotManager 同一谓词）。 */
    private List<WorkerNode> availableNodes(LocalDateTime now) {
        List<WorkerNode> out = new ArrayList<>();
        for (WorkerNode n : nodeRepository.findAll()) {
            if ("ONLINE".equals(n.getStatus())
                    && NodeHealthService.isAvailable(n, now, stabilizationWindowMs,
                    FleetService.OFFLINE_THRESHOLD_SECONDS * 1000L)) {
                out.add(n);
            }
        }
        return out;
    }

    /** FR-014：有 WAITING 实例且有可用节点 → 发 WAKE 兜底抽干（恢复后无残留等待）。 */
    private void wakeIfWaitingAndAvailable(boolean hasAvailableNode) {
        if (!hasAvailableNode) {
            return;
        }
        Integer waiting = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_instance WHERE state='WAITING' AND deleted=0", Integer.class);
        if (waiting != null && waiting > 0) {
            eventBus.publish(InstanceStates.WAKE_CHANNEL, "stuck-sweep");
        }
    }

    /** FR-015：WAITING 实例滞留超阈值且无可用节点 → NODE_STARVATION 告警（不判死）。 */
    private void alertNodeStarvation(LocalDateTime now, boolean hasAvailableNode) {
        if (hasAvailableNode) {
            return;  // 有可用节点，等待会被认领，不告警
        }
        LocalDateTime threshold = now.minusNanos(stuckWaitAlertMs * 1_000_000L);
        Integer stuck = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_instance WHERE state='WAITING' AND deleted=0 AND updated_at < ?",
                Integer.class, threshold);
        if (stuck != null && stuck > 0) {
            publishStarvation(stuck);
        }
    }

    /** FR-012：巡检 SUSPENDED 实例 → 续发 TASK_SUSPENDED 告警交人工（不自动判终态）。 */
    private void alertSuspended() {
        List<SuspendedRow> rows = jdbc.query(
                "SELECT id, tenant_id FROM task_instance WHERE state='SUSPENDED' AND deleted=0",
                (rs, n) -> new SuspendedRow(
                        rs.getObject("id", UUID.class),
                        ((Number) rs.getObject("tenant_id")).longValue()));
        for (SuspendedRow r : rows) {
            publishSuspended(r);
        }
    }

    private void publishStarvation(int stuckCount) {
        try {
            Map<String, Object> ctx = new LinkedHashMap<>();
            ctx.put("stuckWaitingCount", stuckCount);
            ctx.put("stuckWaitAlertMs", stuckWaitAlertMs);
            // tenant 维度取一个卡住实例的租户（无租户列聚合时取首个卡住实例）
            Long tenantId = jdbc.queryForObject(
                    "SELECT tenant_id FROM task_instance WHERE state='WAITING' AND deleted=0 ORDER BY updated_at ASC LIMIT 1",
                    Long.class);
            eventPublisher.publishEvent(new AlertSignal(AlertSignal.Type.NODE_STARVATION,
                    tenantId != null ? tenantId : 0L, "node-starvation", "WARNING", ctx));
            log.warn("[StuckInstanceSweeper] {} 个就绪任务因无可用节点等待超阈值（仅告警，不判死）", stuckCount);
        } catch (Exception e) {
            // 告警 best-effort
        }
    }

    private void publishSuspended(SuspendedRow r) {
        try {
            Map<String, Object> ctx = new LinkedHashMap<>();
            ctx.put("taskInstanceId", r.id.toString());
            ctx.put("failureReason", InstanceStates.INFRA_SUSPENDED);
            eventPublisher.publishEvent(new AlertSignal(AlertSignal.Type.TASK_SUSPENDED,
                    r.tenantId, r.id.toString(), "HIGH", ctx));
        } catch (Exception e) {
            // 告警 best-effort
        }
    }

    private record SuspendedRow(UUID id, long tenantId) {}
}
