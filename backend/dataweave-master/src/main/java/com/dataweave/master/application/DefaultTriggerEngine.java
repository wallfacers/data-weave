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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 触发引擎实现（移植 PowerJob 预读+精确触发思想）。两级时序：
 * <ol>
 *   <li>{@link #scanAndArm} 由 {@code CronScheduler} 每 {@code cron-scan-interval-ms} 调用，捞取
 *       {@code next_trigger_time ≤ now+lookahead} 的工作流，初始化缺失的 next，按精确延迟压入定时器；</li>
 *   <li>到点 {@link #fireArm} 先校验仍 ONLINE 且在生效期内（FR-013），经 {@code cron_fire} 唯一键同步去重（FR-003），
 *       再把物化任务提交 {@link #fireExecutor} 异步消化；{@link #fireExecute} 委托
 *       {@link WorkflowTriggerService#trigger}（签名不变，FR-012/FR-015 允许并发）创建实例并回填 cron_fire，
 *       再据 {@link TimingStrategy} 重算并持久化 next（misfire：fire_once 补一次跳未来 / skip 仅推进，FR-005/006）。</li>
 * </ol>
 * 去重真相仍是 {@code cron_fire} UNIQUE；本引擎进程内、不跨进程共享。崩溃丢失的内存队列任务由
 * {@code CronFireReconciler} 扫描 {@code cron_fire.instance_id IS NULL} 的行补偿（045）。
 *
 * <h3>045 cron 并行化：fireArm / fireExecute 解耦</h3>
 * <p>原 timer 线程（池硬编码 2）同步执行整个 fire（含 ~0.25s 物化）→ 高并发到期被节流（044 实测 1-3 inst/s）。
 * 现拆为：
 * <ul>
 *   <li>{@code fireArm}（timer 线程，池 {@code cron-trigger-timer-threads}）：校验 + INSERT cron_fire(PENDING) +
 *       提交物化任务（μs 级，不阻塞 timer）；</li>
 *   <li>{@code fireExecute}（{@code fireExecutor} 线程，池 {@code cron-fire-worker-threads}）：应用层幂等查 +
 *       物化 + 回填 cron_fire(FIRED) + advanceNext（并发 = worker 数，吞吐 ∝ 池大小）；</li>
 *   <li>满队列降级：{@code fireExecutor} 队列满 → 自定义拒绝策略在 timer 线程同步跑 fireExecute（不丢，
 *       {@link SchedulerMetrics#markQueueFull()} 暴露背压）。</li>
 * </ul>
 * 幂等三层：① cron_fire UNIQUE（谁创建）② workflow_instance UNIQUE(wf, scheduled_fire_time)（DB 兜底）
 * ③ 应用层 findByWorkflowIdAndScheduledFireTime 快查（避免撞键回滚开销）。
 */
@Component
public class DefaultTriggerEngine implements TriggerEngine {

    private static final Logger log = LoggerFactory.getLogger(DefaultTriggerEngine.class);
    private static final DateTimeFormatter BIZ_DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final WorkflowDefRepository workflowDefRepository;
    private final CronFireRepository cronFireRepository;
    private final WorkflowTriggerService triggerService;
    private final WorkflowInstanceRepository workflowInstanceRepository;
    private final SchedulerClock clock;
    private final SchedulerMetrics metrics;
    private final List<TimingStrategy> strategies;
    private final MasterRegistry masterRegistry;
    private final long lookaheadMs;
    private final String misfirePolicy;
    private final boolean shardingEnabled;

    /** 进程内精确触发器；预读窗口内的到期点按延迟入队，到点回调 fireArm。045：池大小可配（原硬编码 2）。 */
    private final ScheduledExecutorService timer;
    /** fireExecute 物化 worker 池（045 并行化核心）：有界队列 + 拒绝策略降级同步，并发创建实例。 */
    private final ThreadPoolExecutor fireExecutor;

    /** 已装入定时器的触发点（wfId@due），防同点跨扫描周期重复 arm。 */
    private final ConcurrentHashMap<String, Boolean> armed = new ConcurrentHashMap<>();

    /** US4 支持的全部周期类型：CRON / FIXED_RATE / FIXED_DELAY。 */
    private static final List<String> PERIODIC_TYPES = List.of("CRON", "FIXED_RATE", "FIXED_DELAY");

    public DefaultTriggerEngine(WorkflowDefRepository workflowDefRepository,
                                CronFireRepository cronFireRepository,
                                WorkflowTriggerService triggerService,
                                WorkflowInstanceRepository workflowInstanceRepository,
                                SchedulerClock clock,
                                SchedulerMetrics metrics,
                                List<TimingStrategy> strategies,
                                MasterRegistry masterRegistry,
                                @Value("${scheduler.cron-lookahead-ms:30000}") long lookaheadMs,
                                @Value("${scheduler.cron-misfire:fire_once}") String misfirePolicy,
                                @Value("${scheduler.cron-sharding-enabled:false}") boolean shardingEnabled,
                                @Value("${scheduler.cron-trigger-timer-threads:8}") int timerThreads,
                                @Value("${scheduler.cron-fire-worker-threads:32}") int workerThreads,
                                @Value("${scheduler.cron-fire-queue-capacity:4000}") int queueCapacity) {
        this.workflowDefRepository = workflowDefRepository;
        this.cronFireRepository = cronFireRepository;
        this.triggerService = triggerService;
        this.workflowInstanceRepository = workflowInstanceRepository;
        this.clock = clock;
        this.metrics = metrics;
        this.strategies = strategies;
        this.masterRegistry = masterRegistry;
        this.lookaheadMs = lookaheadMs;
        this.misfirePolicy = misfirePolicy;
        this.shardingEnabled = shardingEnabled;

        this.timer = Executors.newScheduledThreadPool(timerThreads, r -> {
            Thread t = new Thread(r, "cron-trigger");
            t.setDaemon(true);
            return t;
        });

        // 045 物化 worker 池：有界队列（queueCapacity）+ 自定义拒绝策略 = 调用方（timer）同步执行（降级，不丢）
        RejectedExecutionHandler fallbackSync = (r, exec) -> {
            metrics.markQueueFull();
            log.warn("[TriggerEngine] fireExecutor 队列满，降级同步执行（timer 线程直接物化，背压传导）");
            r.run();  // 调用方同步跑（守"不丢触发"）
        };
        this.fireExecutor = new ThreadPoolExecutor(
                workerThreads, workerThreads, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                r -> { Thread t = new Thread(r, "cron-fire-worker"); t.setDaemon(true); return t; },
                fallbackSync);

        log.info("[TriggerEngine] 045 并行化：timerThreads={} workerThreads={} queueCapacity={}",
                timerThreads, workerThreads, queueCapacity);
    }

    @Override
    public void scanAndArm(LocalDateTime now) {
        LocalDateTime horizon = now.plus(Duration.ofMillis(lookaheadMs));
        // 1) 首轮回填：next_trigger_time=NULL 的周期工作流
        for (String type : PERIODIC_TYPES) {
            List<WorkflowDef> uninitialized =
                    workflowDefRepository.findByScheduleTypeAndStatusAndDeleted(type, "ONLINE", 0);
            for (WorkflowDef wf : uninitialized) {
                if (wf.getNextTriggerTime() != null) {
                    continue;
                }
                try {
                    TimingStrategy strategy = strategyFor(wf.getScheduleType());
                    if (strategy == null) {
                        continue;
                    }
                    LocalDateTime next = strategy.next(wf, baseOf(wf, now));
                    if (next == null) {
                        continue;
                    }
                    if (wf.getScheduleEnd() != null && next.isAfter(wf.getScheduleEnd())) {
                        continue;  // 超出生效期：不回填 next_trigger_time，保持停排（与 advanceNext 一致，防已停排工作流被回填复活）
                    }
                    wf.setNextTriggerTime(next);
                    wf.setUpdatedAt(now);
                    workflowDefRepository.save(wf);
                } catch (Exception e) {
                    log.error("[TriggerEngine] 回填 next_trigger_time id={} 失败：{}", wf.getId(), e.getMessage(), e);
                }
            }
        }
        // 2) 预读窗口扫描：next_trigger_time 非空且在 lookahead 范围内（全部周期类型）
        List<WorkflowDef> candidates;
        if (shardingEnabled) {
            int shardCount = masterRegistry.activeMasterCount();
            int shardIndex = masterRegistry.myShardIndex();
            if (shardCount <= 1 || shardIndex < 0) {
                candidates = workflowDefRepository.findScannableByTypes(PERIODIC_TYPES, "ONLINE", horizon);
            } else {
                candidates = workflowDefRepository.findScannableSharded(
                        PERIODIC_TYPES, "ONLINE", horizon, shardCount, shardIndex);
            }
            metrics.setShardWorkflows(candidates.size());
        } else {
            candidates = workflowDefRepository.findScannableByTypes(PERIODIC_TYPES, "ONLINE", horizon);
        }
        int armedCount = 0;
        for (WorkflowDef wf : candidates) {
            try {
                TimingStrategy strategy = strategyFor(wf.getScheduleType());
                if (strategy == null) {
                    continue;
                }
                LocalDateTime next = wf.getNextTriggerTime();
                if (next == null) {
                    continue;
                }
                if (!next.isAfter(horizon)) {
                    if (armPoint(wf.getId(), next)) {
                        armedCount++;
                    }
                }
            } catch (Exception e) {
                log.error("[TriggerEngine] 扫描工作流 id={} 失败：{}", wf.getId(), e.getMessage(), e);
            }
        }
        metrics.setCronWindowSize(armed.size());
        metrics.setFireQueueSize(fireExecutor.getQueue().size());  // 045 队列深度采样（背压 gauge）
        if (armedCount > 0) {
            log.info("[TriggerEngine] scan: 候选={}, 本轮新装载={}, 分片={}, fireQueue={}",
                    candidates.size(), armedCount, shardingEnabled ? masterRegistry.myShardIndex() : "off",
                    fireExecutor.getQueue().size());
        }
    }

    /** 把 (wfId, due) 按精确延迟压入定时器；若已装载返回 false。 */
    private boolean armPoint(Long wfId, LocalDateTime due) {
        String key = wfId + "@" + due;
        if (armed.putIfAbsent(key, Boolean.TRUE) != null) {
            return false;
        }
        long delayMs = Duration.between(clock.now(), due).toMillis();
        if (delayMs < 0) {
            delayMs = 0;  // 逾期点立即触发（FR-005）
        }
        timer.schedule(() -> {
            try {
                fireArm(wfId, due);  // 045：原同步 fire → fireArm（同步去重 + 入队，不阻塞）
            } catch (Exception e) {
                log.error("[TriggerEngine] fireArm wfId={} due={} 失败：{}", wfId, due, e.getMessage(), e);
            } finally {
                armed.remove(key);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
        return true;
    }

    /**
     * 045 到点同步触发（timer 线程）：校验 ONLINE/生效期/misfire → INSERT cron_fire(PENDING, UNIQUE 去重不变)
     * → 提交 fireExecute 到 fireExecutor（满则拒绝策略降级同步，不丢）。timer 线程不阻塞，物化异步。
     */
    private void fireArm(Long wfId, LocalDateTime due) {
        long start = System.nanoTime();
        try {
            WorkflowDef wf = workflowDefRepository.findById(wfId).orElse(null);
            if (wf == null || wf.getDeleted() == null || wf.getDeleted() != 0
                    || !"ONLINE".equals(wf.getStatus())) {
                return;  // 已下线/删除（FR-013）
            }
            LocalDateTime fireNow = clock.now();
            if (wf.getScheduleStart() != null && due.isBefore(wf.getScheduleStart())) {
                return;
            }
            if (wf.getScheduleEnd() != null && due.isAfter(wf.getScheduleEnd())) {
                advanceNext(wf, fireNow);
                return;  // 超出生效期：停止排程（FR-013）
            }

            TimingStrategy strategy = strategyFor(wf.getScheduleType());
            if (strategy == null) {
                return;
            }

            // misfire=skip 且 due 之后还有点已过期（错过多个）→ 不补、仅推进基准（FR-006）
            if ("skip".equalsIgnoreCase(misfirePolicy)) {
                LocalDateTime afterDue = strategy.next(wf, due);
                if (afterDue != null && !afterDue.isAfter(fireNow)) {
                    advanceNext(wf, fireNow);
                    metrics.markCronMisfire(false);
                    log.info("[TriggerEngine] workflow id={} 错过多点 misfire=skip 跳至未来 due={}", wfId, wf.getNextTriggerTime());
                    return;
                }
            }

            // cron_fire 唯一键同步去重：INSERT 成功者拥有本次触发权（多 master/分片零协调，FR-003）
            CronFire guard = new CronFire(wfId, due);
            guard.setCreatedAt(fireNow);
            guard.setStatus("PENDING");  // 045 生命周期标记（fireExecute 回填 FIRED / reconciler DEAD）
            Long cronFireId;
            try {
                cronFireId = cronFireRepository.save(guard).getId();
            } catch (DataIntegrityViolationException dup) {
                return;  // 别的 master 已触发本点
            }

            // 045 提交物化到 worker 池（异步）；满则拒绝策略在 timer 线程同步跑 fireExecute（降级，不丢）
            final Long cfId = cronFireId;
            fireExecutor.execute(() -> fireExecute(new FireTask(wfId, due, cfId)));
        } finally {
            metrics.recordFireArmLatency(Duration.ofNanos(System.nanoTime() - start));
        }
    }

    /**
     * 045 异步物化（fireExecutor worker / 降级时 timer 线程）：应用层幂等查 → trigger 创建实例 →
     * 回填 cron_fire(FIRED) → advanceNext → metrics。幂等三层挡并发/降级/重试重复。
     */
    void fireExecute(FireTask task) {  // package-private for test
        long start = System.nanoTime();
        try {
            WorkflowDef wf = workflowDefRepository.findById(task.workflowId()).orElse(null);
            if (wf == null) {
                return;  // 已删
            }
            LocalDateTime fireNow = clock.now();

            // 应用层幂等快查：同 (wf, scheduled_fire_time) 已有 instance 则跳过（避免 trigger 撞键回滚开销）
            WorkflowInstance existing = workflowInstanceRepository
                    .findByWorkflowIdAndScheduledFireTime(task.workflowId(), task.due()).orElse(null);
            UUID wiId;
            if (existing != null) {
                metrics.markReconcileSkipped();  // 幂等跳过（复用 skipped 计数）
                wiId = existing.getId();
                log.info("[TriggerEngine] 幂等跳过 wfId={} due={} 已有实例={}", task.workflowId(), task.due(), wiId);
            } else {
                wiId = triggerService.trigger(wf, wf.getScheduleType(),
                        task.due().minusDays(1).format(BIZ_DATE_FMT), wf.getPriority(), Messages.DEFAULT_LOCALE,
                        "FULL", null, "NORMAL", null, 0, task.due());
            }

            // 回填 cron_fire（instance_id + status=FIRED + fired_at）
            cronFireRepository.findById(task.cronFireId()).ifPresent(guard -> {
                guard.setWorkflowInstanceId(wiId);
                guard.setStatus("FIRED");
                guard.setFiredAt(LocalDateTime.now());
                cronFireRepository.save(guard);
            });

            boolean overdue = fireNow.isAfter(task.due());
            wf.setLastFireTime(task.due());
            advanceNext(wf, fireNow);
            if (overdue) {
                metrics.markCronMisfire(true);
            }
            metrics.recordCronTriggerLatency(Duration.between(task.due(), fireNow));
            log.info("[TriggerEngine] 触发 workflow id={} name='{}' due={} 实例={} next={}",
                    task.workflowId(), wf.getName(), task.due(), wiId, wf.getNextTriggerTime());
        } catch (DataIntegrityViolationException dup) {
            // DB 唯一约束兜底（应用层快查 TOCTOU）：并发下已被别处创建，查已有回填（不重复 advanceNext）
            workflowInstanceRepository.findByWorkflowIdAndScheduledFireTime(task.workflowId(), task.due())
                    .ifPresent(wi -> {
                        cronFireRepository.findById(task.cronFireId()).ifPresent(guard -> {
                            guard.setWorkflowInstanceId(wi.getId());
                            guard.setStatus("FIRED");
                            guard.setFiredAt(LocalDateTime.now());
                            cronFireRepository.save(guard);
                        });
                        metrics.markReconcileSkipped();
                        log.info("[TriggerEngine] DB 唯一约束兜底 wfId={} due={} 已有实例={}",
                                task.workflowId(), task.due(), wi.getId());
                    });
        } catch (Exception e) {
            log.error("[TriggerEngine] fireExecute task={} 失败：{}", task, e.getMessage(), e);
        } finally {
            metrics.recordFireExecuteLatency(Duration.ofNanos(System.nanoTime() - start));
        }
    }

    /** 045 物化任务（进程内，timer → worker 传递；不持久化，崩溃由 reconciler 补）。 */
    record FireTask(Long workflowId, LocalDateTime due, Long cronFireId) {}  // package-private for test

    /** 推进 next_trigger_time 到严格大于 ref 的最近点并落库；无后续置 null（停排）。 */
    private void advanceNext(WorkflowDef wf, LocalDateTime ref) {
        TimingStrategy strategy = strategyFor(wf.getScheduleType());
        LocalDateTime next = strategy != null ? strategy.next(wf, ref) : null;
        if (next != null && wf.getScheduleEnd() != null && next.isAfter(wf.getScheduleEnd())) {
            next = null;  // 下一点已超出生效期
        }
        wf.setNextTriggerTime(next);
        wf.setUpdatedAt(ref);
        workflowDefRepository.save(wf);
    }

    private TimingStrategy strategyFor(String scheduleType) {
        for (TimingStrategy s : strategies) {
            if (s.supports(scheduleType)) {
                return s;
            }
        }
        return null;
    }

    private LocalDateTime baseOf(WorkflowDef wf, LocalDateTime now) {
        // FIXED_DELAY: 基准 = 上一实例完成时刻，无历史则用创建时刻
        if ("FIXED_DELAY".equalsIgnoreCase(wf.getScheduleType())) {
            List<WorkflowInstance> finished = workflowInstanceRepository.findByWorkflowId(wf.getId());
            LocalDateTime lastCompletion = null;
            for (WorkflowInstance wi : finished) {
                if (wi.getFinishedAt() != null
                        && (lastCompletion == null || wi.getFinishedAt().isAfter(lastCompletion))) {
                    lastCompletion = wi.getFinishedAt();
                }
            }
            if (lastCompletion != null) {
                return lastCompletion;
            }
            return wf.getCreatedAt() != null ? wf.getCreatedAt() : now.minusMinutes(1);
        }
        // CRON / FIXED_RATE: 基准 = 上次计划触发时刻或创建时刻
        if (wf.getLastFireTime() != null) {
            return wf.getLastFireTime();
        }
        return wf.getCreatedAt() != null ? wf.getCreatedAt() : now.minusMinutes(1);
    }

    @PreDestroy
    void shutdown() {
        timer.shutdownNow();
        fireExecutor.shutdownNow();  // 045 关闭物化 worker 池
    }
}
