package com.dataweave.master.application;

import com.dataweave.master.domain.WorkerNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 060 节点健康服务：基于近期基础设施故障计数的熔断（隔离/复位）+ 纪元稳定窗 + 可用性谓词。
 *
 * <p>并发安全（FR-006）：所有更新走原子/单调 SQL——故障计数 {@code x=x+1}、隔离到期时刻 {@code GREATEST} 只增不减，
 * 对等 master 并发更新同一节点结果与串行等价，无需行锁或乐观 version 重试（research D4）。
 *
 * <p>归因边界（FR-003）：计入熔断的仅"下发不可达 / 运行中执行异常回收（WORKER_LOST）"这类归因于节点自身的事件；
 * {@code WORKER_RESTART}（incarnation 变化的正常重启）由稳定窗（{@link #isAvailable}）处置，**不**经
 * {@link #recordInfraFailure} 计入熔断，避免正常滚动重启的节点被误隔离。
 *
 * <p>可用性三谓词（FR-001/002/003/005）收口在 {@link #isAvailable}，{@link SlotManager}（派发候选）与
 * {@link FleetService}（恢复唤醒判定）共用，保证单点判定一致。
 */
@Service
public class NodeHealthService {

    private final JdbcTemplate jdbc;
    private final int quarantineThreshold;
    private final long quarantineBackoffMs;

    public NodeHealthService(JdbcTemplate jdbc,
                             @Value("${scheduler.node.quarantine-threshold:3}") int quarantineThreshold,
                             @Value("${scheduler.node.quarantine-backoff-ms:30000}") long quarantineBackoffMs) {
        this.jdbc = jdbc;
        this.quarantineThreshold = quarantineThreshold;
        this.quarantineBackoffMs = quarantineBackoffMs;
    }

    /**
     * 记一次基础设施故障：原子自增 {@code consecutive_infra_failures}；跨 {@code quarantine-threshold} 即隔离
     * （{@code quarantined_until = GREATEST(COALESCE(old, now), now+backoff)}，只增不减）。FR-003/006。
     *
     * <p>仅在 infra 回收归因于节点自身（下发不可达 / WORKER_LOST）时调用；WORKER_RESTART 不调用（D4）。
     */
    public void recordInfraFailure(String nodeCode) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime backoff = now.plusNanos(quarantineBackoffMs * 1_000_000L);
        jdbc.update(
                "UPDATE worker_nodes SET consecutive_infra_failures = COALESCE(consecutive_infra_failures, 0) + 1, "
                        + "quarantined_until = CASE WHEN COALESCE(consecutive_infra_failures, 0) + 1 >= ? "
                        + "THEN GREATEST(COALESCE(quarantined_until, ?), ?) ELSE quarantined_until END, "
                        + "updated_at=? WHERE node_code=? AND deleted=0",
                quarantineThreshold, now, backoff, now, nodeCode);
    }

    /**
     * 复位熔断计数：成功执行一次（FR-004）或隔离到期稳定后调用。
     * {@code consecutive_infra_failures=0, quarantined_until=NULL}。
     */
    public void clearOnSuccess(String nodeCode) {
        jdbc.update(
                "UPDATE worker_nodes SET consecutive_infra_failures=0, quarantined_until=NULL, updated_at=? "
                        + "WHERE node_code=? AND deleted=0",
                LocalDateTime.now(), nodeCode);
    }

    /**
     * 纪元变化时重置稳定窗起点（{@code incarnation_since=now}）。
     *
     * <p>主路径由 {@code FleetService.report} 在 upsert 时直接落 incarnation_since（原子、无额外查询），
     * 此方法作为独立原子助手保留，供需要单独刷新稳定窗的调用方使用。
     */
    public void markIncarnationChanged(String nodeCode) {
        jdbc.update(
                "UPDATE worker_nodes SET incarnation_since=?, updated_at=? WHERE node_code=? AND deleted=0",
                LocalDateTime.now(), LocalDateTime.now(), nodeCode);
    }

    /**
     * 节点可用性谓词（FR-001/002/003/005 单点收口）：心跳新鲜 + 纪元过稳定窗 + 未隔离。
     *
     * <ul>
     *   <li>心跳不新鲜（超过 offlineThreshold 未上报）→ 排除（FR-001）。</li>
     *   <li>纪元未过稳定窗（incarnation_since 距 now &lt; stabilizationWindow；专治抖动假节点/刚重启）→ 排除（FR-002）。
     *       {@code incarnation_since IS NULL} 视为老数据/稳定，放行；新注册节点由 FleetService 落 now，首个稳定窗内自然不被派。</li>
     *   <li>隔离中（{@code quarantined_until > now}）→ 排除（FR-003）。</li>
     * </ul>
     *
     * @param stabilizationWindowMs 稳定窗（{@code scheduler.node.stabilization-window-ms}）
     * @param offlineThresholdMs     心跳离线阈值（对齐 {@code FleetService.OFFLINE_THRESHOLD_SECONDS}）
     */
    public static boolean isAvailable(WorkerNode node, LocalDateTime now,
                                      long stabilizationWindowMs, long offlineThresholdMs) {
        if (node == null || node.getLastHeartbeat() == null) {
            return false;
        }
        if (node.getLastHeartbeat().isBefore(now.minusNanos(offlineThresholdMs * 1_000_000L))) {
            return false;  // 心跳不新鲜
        }
        if (node.getIncarnationSince() != null
                && node.getIncarnationSince().isAfter(now.minusNanos(stabilizationWindowMs * 1_000_000L))) {
            return false;  // 纪元未过稳定窗
        }
        if (node.getQuarantinedUntil() != null && node.getQuarantinedUntil().isAfter(now)) {
            return false;  // 隔离中
        }
        return true;
    }
}
