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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 触发引擎实现（移植 PowerJob 预读+精确触发思想）。两级时序：
 * <ol>
 *   <li>{@link #scanAndArm} 由 {@code CronScheduler} 每 {@code cron-scan-interval-ms} 调用，捞取
 *       {@code next_trigger_time ≤ now+lookahead} 的工作流，初始化缺失的 next，按精确延迟压入定时器；</li>
 *   <li>到点 {@link #fire} 先校验仍 ONLINE 且在生效期内（FR-013），经 {@code cron_fire} 唯一键去重（FR-003），
 *       委托 {@link WorkflowTriggerService#trigger}（签名不变，FR-012/FR-015 允许并发），
 *       再据 {@link TimingStrategy} 重算并持久化 next（misfire：fire_once 补一次跳未来 / skip 仅推进，FR-005/006）。</li>
 * </ol>
 * 去重真相仍是 {@code cron_fire}；本引擎进程内、不跨进程共享。崩溃丢失的内存点由下一轮扫描 + 逾期 delay=0 补回。
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

    /** 进程内精确触发器；预读窗口内的到期点按延迟入队，到点回调 fire。 */
    private final ScheduledExecutorService timer =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "cron-trigger");
                t.setDaemon(true);
                return t;
            });
    /** 已装入定时器的触发点（wfId@due），防同点跨扫描周期重复 arm。 */
    private final ConcurrentHashMap<String, Boolean> armed = new ConcurrentHashMap<>();

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
                                @Value("${scheduler.cron-sharding-enabled:false}") boolean shardingEnabled) {
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
    }

    /** US4 支持的全部周期类型：CRON / FIXED_RATE / FIXED_DELAY。 */
    private static final List<String> PERIODIC_TYPES = List.of("CRON", "FIXED_RATE", "FIXED_DELAY");

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
                // 分片未就绪（只有自己在线 / 未注册）→ 退化为全量扫描
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
        if (armedCount > 0) {
            log.info("[TriggerEngine] scan: 候选={}, 本轮新装载={}, 分片={}",
                    candidates.size(), armedCount, shardingEnabled ? masterRegistry.myShardIndex() : "off");
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
                fire(wfId, due);
            } catch (Exception e) {
                log.error("[TriggerEngine] 触发 wfId={} due={} 失败：{}", wfId, due, e.getMessage(), e);
            } finally {
                armed.remove(key);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
        return true;
    }

    /** 到点触发：失效校验 → cron_fire 去重 → 下游 trigger → 重算并持久化 next。 */
    private void fire(Long wfId, LocalDateTime due) {
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

        // cron_fire 唯一键去重：插入成功者拥有本次触发，撞键安全放弃（多 master/分片零协调，FR-003）
        CronFire guard = new CronFire(wfId, due);
        guard.setCreatedAt(fireNow);
        try {
            cronFireRepository.save(guard);
        } catch (DataIntegrityViolationException dup) {
            return;  // 别的 master 已触发本点
        }

        // 下游触发（签名不变，FR-012）；重叠不阻塞、允许并发（FR-015）
        // triggerType 使用实际的 schedule_type，方便区分 CRON/FIXED_RATE/FIXED_DELAY 触发
        UUID wiId = triggerService.trigger(wf, wf.getScheduleType(),
                due.minusDays(1).format(BIZ_DATE_FMT), wf.getPriority(), Messages.DEFAULT_LOCALE);
        guard.setWorkflowInstanceId(wiId);
        guard.setFiredAt(LocalDateTime.now());
        cronFireRepository.save(guard);

        boolean overdue = fireNow.isAfter(due);
        // 重算 next：跳到“以 fireNow 为基准的未来最近点”，逾期不逐个回放（fire_once，FR-006）
        wf.setLastFireTime(due);
        advanceNext(wf, fireNow);
        if (overdue) {
            metrics.markCronMisfire(true);
        }
        metrics.recordCronTriggerLatency(Duration.between(due, fireNow));
        log.info("[TriggerEngine] 触发 workflow id={} name='{}' due={} 实例={} next={}",
                wfId, wf.getName(), due, wiId, wf.getNextTriggerTime());
    }

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
    }
}
