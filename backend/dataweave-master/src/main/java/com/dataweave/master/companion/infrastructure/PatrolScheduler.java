package com.dataweave.master.companion.infrastructure;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.dataweave.master.companion.application.PatrolService;
import com.dataweave.master.companion.domain.PatrolRoutine;
import com.dataweave.master.companion.domain.PatrolRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 巡检调度器（T018）。完全套用调度内核既有模式，守调度四不变量：
 * <ul>
 *   <li><b>arm</b>（{@link #armTick}）：扫启用例程，cron 算出到点 fire_time，
 *       {@code patrol_run UNIQUE(routine_id, scheduled_fire_time)} 单赢 INSERT CLAIMED（抄 cron_fire，不变量①幂等形态）。</li>
 *   <li><b>claim</b>（{@link #claimTick}）：{@code FOR UPDATE SKIP LOCKED} 认领 CLAIMED（不变量①），
 *       事务内 CAS CLAIMED→RUNNING（不变量②④），事务提交后才异步 brain 外呼（不变量④，事务外）。</li>
 *   <li><b>reaper</b>（{@link #reaperTick}）：RUNNING 超 {@code timeout_seconds} → CAS TIMEOUT + 兜底未完成汇报。</li>
 * </ul>
 *
 * <p>多 master 对等：都跑同一套 @Scheduled，靠 DB 层（UNIQUE 单赢 + SKIP LOCKED + CAS）裁决，无中心协调。
 * {@code companion.patrol.enabled=false} 可整体停用（测试防串扰用）。
 */
@Component
public class PatrolScheduler {

    private static final Logger log = LoggerFactory.getLogger(PatrolScheduler.class);
    /** 首次 arm（无历史 run）的回看窗口上限，防一次 arm 过多积压槽位。 */
    private static final long FIRST_RUN_LOOKBACK_HOURS = 2;
    /** 单次 arm 迭代上限（防御异常 cron 死循环）。 */
    private static final int ARM_ITERATION_CAP = 200;

    private final JdbcPatrolRoutineRepository routineRepo;
    private final JdbcPatrolRunRepository runRepo;
    private final PatrolService patrolService;
    private final TransactionTemplate txTemplate;
    private final boolean enabled;
    private final int claimBatch;

    public PatrolScheduler(JdbcPatrolRoutineRepository routineRepo, JdbcPatrolRunRepository runRepo,
                           PatrolService patrolService, PlatformTransactionManager txManager,
                           @Value("${companion.patrol.enabled:true}") boolean enabled,
                           @Value("${companion.patrol.claim-batch:10}") int claimBatch) {
        this.routineRepo = routineRepo;
        this.runRepo = runRepo;
        this.patrolService = patrolService;
        this.txTemplate = new TransactionTemplate(txManager);
        this.enabled = enabled;
        this.claimBatch = claimBatch;
    }

    /** arm：扫启用例程，到点的 fire_time 单赢落 CLAIMED run（抄 cron_fire 幂等地基）。 */
    @Scheduled(fixedRateString = "${companion.patrol.arm-interval-ms:15000}")
    public void armTick() {
        if (!enabled) return;
        try {
            LocalDateTime now = LocalDateTime.now();
            for (PatrolRoutine r : routineRepo.findAllEnabled()) {
                armIfDue(r, now);
            }
        } catch (Exception e) {
            log.warn("[PatrolScheduler] armTick 异常: {}", e.toString());
        }
    }

    private void armIfDue(PatrolRoutine r, LocalDateTime now) {
        CronExpression cron;
        try {
            cron = CronExpression.parse(r.cronExpression());
        } catch (IllegalArgumentException e) {
            log.warn("[PatrolScheduler] 例程 {} cron 非法({})，跳过", r.id(), r.cronExpression());
            return;
        }
        Optional<LocalDateTime> lastArmed = runRepo.findLastFireTime(r.id());
        LocalDateTime from = lastArmed.orElse(now.minusHours(FIRST_RUN_LOOKBACK_HOURS));
        // 从上次已 arm（或回看窗口）往后走到 now，取最近的到点槽位
        LocalDateTime due = null;
        LocalDateTime t = cron.next(from);
        int guard = 0;
        while (t != null && !t.isAfter(now) && guard++ < ARM_ITERATION_CAP) {
            due = t;
            t = cron.next(t);
        }
        if (due == null) return;
        // UNIQUE 单赢：已 arm 则 tryClaimCreate 返回 empty（no-op）；成功者拥有本次触发
        runRepo.tryClaimCreate(r.tenantId(), r.projectId(), r.id(), "SCHEDULED", due);
    }

    /** claim：SKIP LOCKED 认领 CLAIMED，事务内 CAS→RUNNING，提交后异步 brain 外呼（事务外，不变量④）。 */
    @Scheduled(fixedRateString = "${companion.patrol.claim-interval-ms:5000}")
    public void claimTick() {
        if (!enabled) return;
        try {
            List<PatrolRun> claimed = txTemplate.execute(status -> {
                List<PatrolRun> rows = runRepo.pollClaimed(claimBatch);   // FOR UPDATE SKIP LOCKED
                for (PatrolRun row : rows) {
                    runRepo.casStart(row.id());                            // CLAIMED→RUNNING（事务内）
                }
                return rows;
            });
            if (claimed != null) {
                for (PatrolRun r : claimed) {
                    patrolService.executeRunAsync(r.id());                 // 事务外异步 brain 外呼
                }
            }
        } catch (Exception e) {
            log.warn("[PatrolScheduler] claimTick 异常: {}", e.toString());
        }
    }

    /** reaper：RUNNING 超 timeout_seconds → CAS TIMEOUT + 兜底未完成汇报（SC-007）。 */
    @Scheduled(fixedRateString = "${companion.patrol.reaper-interval-ms:30000}")
    public void reaperTick() {
        if (!enabled) return;
        try {
            LocalDateTime now = LocalDateTime.now();
            for (JdbcPatrolRunRepository.RunningRun c : runRepo.findRunningCandidates()) {
                LocalDateTime deadline = c.startedAt().plusSeconds(c.timeoutSeconds());
                if (deadline.isBefore(now)) {
                    if (runRepo.markTimeout(c.id(), "巡检超时(" + c.timeoutSeconds() + "s)")) {
                        patrolService.produceTimeoutReport(c.id());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[PatrolScheduler] reaperTick 异常: {}", e.toString());
        }
    }
}
