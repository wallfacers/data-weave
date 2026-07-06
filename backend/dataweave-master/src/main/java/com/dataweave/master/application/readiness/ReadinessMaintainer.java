package com.dataweave.master.application.readiness;

import com.dataweave.master.application.SchedulerMetrics;
import com.dataweave.master.domain.EventBus;
import com.dataweave.master.domain.InstanceStates;
import com.dataweave.master.infrastructure.JdbcReadinessSignalRepository;
import com.dataweave.master.infrastructure.ReadinessSignalRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 051 就绪态物化：sweeper 轮询信号 → 解析受影响下游集 D → scoped 重算 → CAS 落库 → wake。
 *
 * <p>每 {@code scheduler.readiness.maintainer.interval} 跑一批：SKIP LOCKED 批量领取未处理信号，
 * 对每条信号解析 D，调 ReadinessRecompute 重算，CAS UPDATE task_instance，标记信号已处理。
 * 有下游变为就绪（unmet_deps>0 → 0）时 wake() 促一轮认领。
 *
 * <p>幂等：重复处理同信号 → ReadinessRecompute 读权威态 → 同结果，安全。
 */
@Service
public class ReadinessMaintainer {

    private static final Logger log = LoggerFactory.getLogger(ReadinessMaintainer.class);

    private final JdbcReadinessSignalRepository signalRepo;
    private final ReadinessRecompute recompute;
    private final SchedulerMetrics metrics;
    private final EventBus eventBus;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate txTemplate;
    private final int intervalMs;
    private final int batchSize;
    private final boolean enabled;

    public ReadinessMaintainer(JdbcReadinessSignalRepository signalRepo,
                               ReadinessRecompute recompute,
                               SchedulerMetrics metrics,
                               EventBus eventBus,
                               JdbcTemplate jdbc,
                               PlatformTransactionManager txManager,
                               @Value("${scheduler.readiness.maintainer.interval-ms:1000}") int intervalMs,
                               @Value("${scheduler.readiness.maintainer.batch:100}") int batchSize) {
        this.signalRepo = signalRepo;
        this.recompute = recompute;
        this.metrics = metrics;
        this.eventBus = eventBus;
        this.jdbc = jdbc;
        this.txTemplate = new TransactionTemplate(txManager);
        this.intervalMs = intervalMs;
        this.batchSize = batchSize;
        this.enabled = intervalMs > 0;
    }

    /**
     * 周期轮询未处理信号，处理并维护就绪态。
     */
    @Scheduled(fixedRateString = "${scheduler.readiness.maintainer.interval-ms:1000}")
    public void maintain() {
        if (!enabled) return;
        try {
            txTemplate.executeWithoutResult(status -> maintainInTx());
        } catch (Exception e) {
            log.warn("[Maintainer] 本轮处理异常：{}", e.getMessage());
        }
        // 更新积压 gauge
        try {
            metrics.setReadinessSignalPending(signalRepo.countPending());
        } catch (Exception ex) {
            // 静默吞错
        }
    }

    private void maintainInTx() {
        List<ReadinessSignalRow> signals = signalRepo.pollPending(batchSize);
        if (signals.isEmpty()) {
            metrics.setReadinessSignalPending(0);
            return;
        }

        int totalDownstream = 0;
        List<Long> processedIds = new ArrayList<>();
        boolean anyNewReady = false;

        for (ReadinessSignalRow signal : signals) {
            try {
                // 计算信号滞后（创建 → 处理）
                if (signal.createdAt() != null) {
                    metrics.recordReadinessSignalLag(
                            Duration.between(signal.createdAt(), LocalDateTime.now()));
                }

                // 解析下游 D 并重算
                Map<UUID, Integer> newUnmets = recompute.recomputeFromTerminal(signal.upstreamInstanceId());
                if (newUnmets.isEmpty()) {
                    processedIds.add(signal.id());
                    continue;
                }

                metrics.recordReadinessRecomputeScope(newUnmets.size());
                totalDownstream += newUnmets.size();

                // CAS UPDATE task_instance SET unmet_deps = ? WHERE id = ?
                for (var entry : newUnmets.entrySet()) {
                    UUID instanceId = entry.getKey();
                    int newUnmet = entry.getValue();
                    int prevUnmet = getCurrentUnmet(instanceId);
                    jdbc.update("UPDATE task_instance SET unmet_deps = ?, updated_at = ? WHERE id = ?",
                            newUnmet, LocalDateTime.now(), instanceId);
                    if (prevUnmet > 0 && newUnmet == 0) {
                        anyNewReady = true;
                        log.debug("[Maintainer] 实例 {} 变为就绪 (unmet {}→0)", instanceId, prevUnmet);
                    }
                }

                processedIds.add(signal.id());
            } catch (Exception e) {
                log.warn("[Maintainer] 处理信号 {} 失败：{}", signal.id(), e.getMessage());
                // 不标记 processed，下轮重试
            }
        }

        // 批量标记已处理
        if (!processedIds.isEmpty()) {
            signalRepo.markProcessed(processedIds);
        }

        metrics.markReadinessMaintainBatch(totalDownstream);

        // 有新就绪实例 → wake 促一轮认领
        if (anyNewReady) {
            eventBus.publish(InstanceStates.WAKE_CHANNEL, "maintainer");
        }
    }

    private int getCurrentUnmet(UUID instanceId) {
        try {
            Integer v = jdbc.queryForObject(
                    "SELECT unmet_deps FROM task_instance WHERE id = ?", Integer.class, instanceId);
            return v != null ? v : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}
