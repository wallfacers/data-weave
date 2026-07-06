package com.dataweave.master.application.readiness;

import com.dataweave.master.application.SchedulerMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 051 就绪态物化：低频有界对账自愈 + 上线一次性全量初始化。
 *
 * <p>正常路径（outbox + 重算幂等）已构造无漂移；对账只兜 bug/手工改库/torn-state 的残留。
 * <p>定向审计：WAITING 且 unmet_deps>0 且 updated_at 停留超阈值（默认 2×reconciler interval）的实例。
 *
 * <p>上线一次性初始化（R9/§6=A）：启动时对 WAITING 全量算初值；
 * {@code scheduler.readiness.materialized} 门住认领 unmet_deps=0 过滤直到初始化完成。
 */
@Service
public class ReadinessReconciler {

    private static final Logger log = LoggerFactory.getLogger(ReadinessReconciler.class);

    private final JdbcTemplate jdbc;
    private final ReadinessRecompute recompute;
    private final SchedulerMetrics metrics;
    private final TransactionTemplate txTemplate;
    private final int intervalMs;
    private final int windowSize;

    /** 门控开关：全量初始化完成后置 true，认领才启用 unmet_deps=0 过滤。 */
    private final AtomicBoolean materialized = new AtomicBoolean(false);

    /** 全量初始化是否已完成。 */
    private final AtomicBoolean backfillDone = new AtomicBoolean(false);

    public ReadinessReconciler(JdbcTemplate jdbc,
                                ReadinessRecompute recompute,
                                SchedulerMetrics metrics,
                                PlatformTransactionManager txManager,
                                @Value("${scheduler.readiness.reconciler.interval-ms:60000}") int intervalMs,
                                @Value("${scheduler.readiness.reconciler.window:500}") int windowSize,
                                @Value("${scheduler.readiness.materialized:false}") boolean initialMaterialized) {
        this.jdbc = jdbc;
        this.recompute = recompute;
        this.metrics = metrics;
        this.txTemplate = new TransactionTemplate(txManager);
        this.intervalMs = intervalMs;
        this.windowSize = windowSize;
        this.materialized.set(initialMaterialized);
    }

    /**
     * 门控开关：认领路径读此值决定是否启用 unmet_deps=0 过滤。
     */
    public boolean isMaterialized() {
        return materialized.get();
    }

    /**
     * 全量初始化是否已完成。
     */
    public boolean isBackfillDone() {
        return backfillDone.get();
    }

    /**
     * 上线一次性全量初始化：对 WAITING 实例批量化重算 unmet_deps 初值。
     * 完成后置 materialized=true 开门。
     */
    public void backfillAll() {
        if (backfillDone.get()) {
            log.info("[Reconciler] 全量初始化已完成，跳过");
            return;
        }
        log.info("[Reconciler] 开始全量初始化（WAITING 实例 unmet_deps 回填）...");
        int offset = 0;
        int totalUpdated = 0;
        while (true) {
            List<UUID> batch = jdbc.query(
                    "SELECT id FROM task_instance WHERE state = 'WAITING' AND deleted = 0 " +
                    "ORDER BY id ASC LIMIT ? OFFSET ?",
                    (rs, n) -> rs.getObject("id", UUID.class),
                    windowSize, offset);
            if (batch.isEmpty()) break;

            Map<UUID, Integer> newUnmets = recompute.recompute(batch);
            int batchUpdated = 0;
            for (var entry : newUnmets.entrySet()) {
                jdbc.update("UPDATE task_instance SET unmet_deps = ?, updated_at = ? WHERE id = ?",
                        entry.getValue(), LocalDateTime.now(), entry.getKey());
                batchUpdated++;
            }
            totalUpdated += batchUpdated;
            offset += windowSize;
            log.debug("[Reconciler] 回填进度：offset={}, batch={}", offset, batchUpdated);
        }
        backfillDone.set(true);
        materialized.set(true);
        log.info("[Reconciler] 全量初始化完成，共更新 {} 实例，materialized=true 门已开", totalUpdated);
    }

    /**
     * 低频有界对账：定向审计停留超阈值或滚动窗内的 WAITING 实例。
     */
    @Scheduled(fixedRateString = "${scheduler.readiness.reconciler.interval-ms:60000}")
    public void reconcile() {
        if (!backfillDone.get()) {
            // 若尚未全量初始化，先执行（幂等）
            backfillAll();
            return;
        }
        try {
            txTemplate.executeWithoutResult(status -> reconcileInTx());
        } catch (Exception e) {
            log.warn("[Reconciler] 对账异常：{}", e.getMessage());
        }
    }

    private void reconcileInTx() {
        // 定向审计：WAITING + unmet_deps>0 + updated_at 停留超 2×间隔
        LocalDateTime staleThreshold = LocalDateTime.now().minusSeconds(intervalMs * 2L / 1000);
        List<UUID> stale = jdbc.query(
                "SELECT id FROM task_instance WHERE state = 'WAITING' AND deleted = 0 " +
                "AND unmet_deps > 0 AND updated_at < ? ORDER BY id ASC LIMIT ?",
                (rs, n) -> rs.getObject("id", UUID.class),
                staleThreshold, windowSize / 2);

        // 滚动窗补充：最近更新的 WAITING 实例（兜底漏网之鱼）
        int remaining = windowSize - stale.size();
        List<UUID> recent = remaining > 0 ? jdbc.query(
                "SELECT id FROM task_instance WHERE state = 'WAITING' AND deleted = 0 " +
                "ORDER BY updated_at DESC LIMIT ?",
                (rs, n) -> rs.getObject("id", UUID.class), remaining) : List.of();

        List<UUID> allToCheck = new ArrayList<>(stale);
        allToCheck.addAll(recent);
        if (allToCheck.isEmpty()) return;

        Map<UUID, Integer> newUnmets = recompute.recompute(allToCheck);
        long driftCount = 0;
        for (var entry : newUnmets.entrySet()) {
            UUID id = entry.getKey();
            int correct = entry.getValue();
            Integer current = jdbc.queryForObject(
                    "SELECT unmet_deps FROM task_instance WHERE id = ?", Integer.class, id);
            if (current != null && current != correct) {
                jdbc.update("UPDATE task_instance SET unmet_deps = ?, updated_at = ? WHERE id = ?",
                        correct, LocalDateTime.now(), id);
                driftCount++;
                log.info("[Reconciler] 漂移修复：实例 {} unmet {}→{}", id, current, correct);
            }
        }
        if (driftCount > 0) {
            metrics.markReadinessDriftCorrected(driftCount);
            log.info("[Reconciler] 本轮修复 {} 条漂移", driftCount);
        }
    }
}
