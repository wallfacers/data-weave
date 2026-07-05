package com.dataweave.master.application;

import com.dataweave.master.domain.CronFire;
import com.dataweave.master.domain.CronFireRepository;
import com.dataweave.master.domain.WorkflowDef;
import com.dataweave.master.domain.WorkflowDefRepository;
import com.dataweave.master.domain.WorkflowInstance;
import com.dataweave.master.domain.WorkflowInstanceRepository;
import com.dataweave.master.i18n.Messages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * 045 cron_fire 补偿器：周期扫描 {@code cron_fire} 中 {@code workflow_instance_id IS NULL} 且超 grace 期的
 * 触发点（进程崩溃丢失的内存队列 FireTask），幂等重试创建实例。
 *
 * <p>幂等三层防护并发重复：① cron_fire UNIQUE(谁创建) ② workflow_instance UNIQUE(wf, scheduled_fire_time)
 * (DB 兜底) ③ 应用层 {@code findByWorkflowIdAndScheduledFireTime} 快查。多 master 并发扫同 NULL 行 →
 * trigger 撞 DB 唯一约束 → catch → 查已有回填（安全，零协调）。
 *
 * <p>DEAD：超 {@code cron-reconcile-timeout-ms} 仍失败的触发点标 {@code status=DEAD} + 告警，避免无限重试占资源。
 * 正常无崩溃时本组件空转（扫到 0 行），不产生额外负载。
 */
@Component
public class CronFireReconciler {

    private static final Logger log = LoggerFactory.getLogger(CronFireReconciler.class);
    private static final DateTimeFormatter BIZ_DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int BATCH = 200;

    private final CronFireRepository cronFireRepository;
    private final WorkflowDefRepository workflowDefRepository;
    private final WorkflowInstanceRepository workflowInstanceRepository;
    private final WorkflowTriggerService triggerService;
    private final SchedulerClock clock;
    private final SchedulerMetrics metrics;
    private final JdbcTemplate jdbc;
    private final long graceMs;
    private final long timeoutMs;

    public CronFireReconciler(CronFireRepository cronFireRepository,
                              WorkflowDefRepository workflowDefRepository,
                              WorkflowInstanceRepository workflowInstanceRepository,
                              WorkflowTriggerService triggerService,
                              SchedulerClock clock,
                              SchedulerMetrics metrics,
                              JdbcTemplate jdbc,
                              @Value("${scheduler.cron-reconcile-grace-ms:30000}") long graceMs,
                              @Value("${scheduler.cron-reconcile-timeout-ms:180000}") long timeoutMs) {
        this.cronFireRepository = cronFireRepository;
        this.workflowDefRepository = workflowDefRepository;
        this.workflowInstanceRepository = workflowInstanceRepository;
        this.triggerService = triggerService;
        this.clock = clock;
        this.metrics = metrics;
        this.jdbc = jdbc;
        this.graceMs = graceMs;
        this.timeoutMs = timeoutMs;
    }

    /**
     * 周期扫描补偿（默认 10s）：捞 instance 未回填 && 超 grace 的 cron_fire，幂等重试。
     * 正常无崩溃时扫到 0 行即返回（空转），仅崩溃/卡住时才介入。
     */
    @Scheduled(fixedRateString = "${scheduler.cron-reconcile-interval-ms:10000}")
    public void reconcile() {
        LocalDateTime now = clock.now();
        // grace 阈值（毫秒→秒精度够用）：fire 后此时间内未回填视为丢失
        LocalDateTime graceThreshold = now.minusNanos(graceMs * 1_000_000);
        LocalDateTime deadThreshold = now.minusNanos(timeoutMs * 1_000_000);
        try {
            List<Long> staleIds = jdbc.queryForList(
                    "SELECT id FROM cron_fire WHERE workflow_instance_id IS NULL "
                            + "AND status <> 'DEAD' AND created_at < ? ORDER BY created_at LIMIT ?",
                    Long.class, graceThreshold, BATCH);
            if (staleIds.isEmpty()) {
                return;
            }
            log.info("[Reconciler] 扫到 {} 个未回填 cron_fire（grace>{}ms）", staleIds.size(), graceMs);
            int replayed = 0, skipped = 0, dead = 0;
            for (Long id : staleIds) {
                CronFire guard = cronFireRepository.findById(id).orElse(null);
                if (guard == null || guard.getWorkflowInstanceId() != null) {
                    continue;  // 已回填（并发被 fireExecute 处理）
                }
                // DEAD 检查：超 timeout 仍失败 → 放弃
                if (guard.getCreatedAt() != null && guard.getCreatedAt().isBefore(deadThreshold)) {
                    dead += markDead(guard);
                    continue;
                }
                WorkflowDef wf = workflowDefRepository.findById(guard.getWorkflowId()).orElse(null);
                if (wf == null || wf.getDeleted() == null || wf.getDeleted() != 0
                        || !"ONLINE".equals(wf.getStatus())) {
                    dead += markDead(guard);  // wf 已删/下线，放弃
                    continue;
                }
                // 幂等查：同 (wf, scheduled_fire_time) 已有 instance？
                WorkflowInstance existing = workflowInstanceRepository
                        .findByWorkflowIdAndScheduledFireTime(guard.getWorkflowId(), guard.getScheduledFireTime())
                        .orElse(null);
                UUID wiId;
                if (existing != null) {
                    wiId = existing.getId();
                    metrics.markReconcileSkipped();
                    skipped++;
                } else {
                    try {
                        wiId = triggerService.trigger(wf, wf.getScheduleType(),
                                guard.getScheduledFireTime().minusDays(1).format(BIZ_DATE_FMT),
                                wf.getPriority(), Messages.DEFAULT_LOCALE,
                                "FULL", null, "NORMAL", null, 0, guard.getScheduledFireTime());
                        metrics.markReconcileReplayed();
                        replayed++;
                    } catch (Exception e) {
                        // 撞 DB 唯一约束（并发被别处创建）或临时故障：下轮再试（未 DEAD）
                        log.warn("[Reconciler] 重试触发失败 cron_fire_id={} wfId={}：{}", id, guard.getWorkflowId(), e.getMessage());
                        continue;
                    }
                }
                // 回填 cron_fire
                guard.setWorkflowInstanceId(wiId);
                guard.setStatus("FIRED");
                guard.setFiredAt(LocalDateTime.now());
                cronFireRepository.save(guard);
            }
            if (replayed > 0 || skipped > 0 || dead > 0) {
                log.info("[Reconciler] 本轮 replayed={} skipped={} dead={}", replayed, skipped, dead);
            }
        } catch (Exception e) {
            log.error("[Reconciler] 扫描失败：{}", e.getMessage(), e);
        }
    }

    /** 标 DEAD + 告警（返回 1 便于计数）。 */
    private int markDead(CronFire guard) {
        guard.setStatus("DEAD");
        cronFireRepository.save(guard);
        metrics.markReconcileDead();
        log.error("[Reconciler] cron_fire DEAD（wfId={} due={} createdAt={} 超时未触发，放弃）",
                guard.getWorkflowId(), guard.getScheduledFireTime(), guard.getCreatedAt());
        return 1;
    }
}
