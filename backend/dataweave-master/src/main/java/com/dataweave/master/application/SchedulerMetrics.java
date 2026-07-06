package com.dataweave.master.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 调度四层可观测指标（design D14，task 5.1/5.2）。
 *
 * <p>所有指标经 Micrometer 注册，通过 actuator 和 {@code /api/ops/metrics} 双通道暴露。
 * 调度内核各组件在关键路径调用本服务方法埋点。
 *
 * <h3>第 1 层：调度性能</h3>
 * <ul>
 *   <li>{@code scheduler.dispatch.latency} — 实例可运行→DISPATCHED 延迟分布</li>
 *   <li>{@code scheduler.dispatch.latency.delivery} — DISPATCHED→RUNNING 下发延迟分布</li>
 *   <li>{@code scheduler.queue.depth} — 当前 WAITING 队列深度</li>
 *   <li>{@code scheduler.queue.oldest.age.seconds} — 最长等待者年龄</li>
 *   <li>{@code scheduler.claim.rounds} — 认领轮次总数</li>
 *   <li>{@code scheduler.claim.empty} — 空认领轮次</li>
 *   <li>{@code scheduler.wake.source} — 唤醒来源（event/poll）</li>
 *   <li>{@code scheduler.round.duration} — 单轮调度耗时分布</li>
 *   <li>{@code scheduler.dispatch.count} — 下发计数</li>
 * </ul>
 *
 * <h3>第 2 层：资源与执行</h3>
 * <ul>
 *   <li>{@code scheduler.slot.utilization} — 全局槽位利用率 0.0–1.0</li>
 *   <li>{@code scheduler.slot.fragmentation} — 有空槽但无可派任务的比例</li>
 *   <li>{@code scheduler.task.duration} — 任务执行耗时分布</li>
 *   <li>{@code scheduler.task.completed} — 任务完成计数（含 outcome tag）</li>
 *   <li>{@code scheduler.lease.reclaim} — 租约过期回收次数</li>
 * </ul>
 *
 * <h3>第 3 层：管道健康（预留，Phase 4 落管道后激活）</h3>
 * <ul>
 *   <li>{@code scheduler.log.e2e.latency} — 日志端到端延迟</li>
 *   <li>{@code scheduler.log.stream.backlog} — Stream 积压行数</li>
 *   <li>{@code scheduler.sse.connections} — SSE 活跃连接数</li>
 * </ul>
 */
@Service
public class SchedulerMetrics {

    private static final Logger log = LoggerFactory.getLogger(SchedulerMetrics.class);

    private final MeterRegistry registry;
    private final JdbcTemplate jdbc;

    private final Timer dispatchLatency;
    private final Timer deliveryLatency;
    private final Timer roundDuration;
    private final Timer taskDuration;
    private final Counter claimRounds;
    private final Counter emptyClaims;
    private final Counter wakeEvent;
    private final Counter wakePoll;
    private final Counter dispatchCount;
    private final Counter leaseReclaims;
    private final Timer cronTriggerLatency;
    private final Counter cronMisfireFireOnce;
    private final Counter cronMisfireSkip;
    private final AtomicLong queueDepth = new AtomicLong(0);
    private final AtomicLong oldestAgeSeconds = new AtomicLong(0);
    private final AtomicLong slotUtilization = new AtomicLong(0);
    private final AtomicLong slotFragmentation = new AtomicLong(0);
    private final AtomicLong logStreamBacklog = new AtomicLong(0);
    private final AtomicLong sseConnections = new AtomicLong(0);
    private final AtomicLong shardWorkflows = new AtomicLong(0);
    private final AtomicLong cronWindowSize = new AtomicLong(0);
    // 045 cron 并行化指标：fireArm/fireExecute 解耦 + 队列背压 + 补偿器
    private final Timer fireExecuteLatency;
    private final Timer fireArmLatency;
    private final Counter fireQueueFull;
    private final Counter reconcileReplayed;
    private final Counter reconcileSkipped;
    private final Counter reconcileDead;
    private final AtomicLong fireQueueSize = new AtomicLong(0);
    // 046 dispatch 并行化：claim/dispatch 解耦(队列 + 异步 executor)背压观测
    private final Counter dispatchQueueFull;
    private final AtomicLong dispatchQueueSize = new AtomicLong(0);
    // 049-收尾：claim 防饿死游标续窗触发数(前缀被未就绪实例占满的信号) + 滞留下发丢弃数(双跑防护命中)
    private final Counter claimExtraWindow;
    private final Counter staleDispatchSkip;

    // 051 就绪态物化指标
    private final Timer readinessSignalLag;
    private final Counter readinessMaintainBatch;
    private final AtomicLong readinessSignalPending = new AtomicLong(0);
    private final Counter readinessDriftCorrected;
    private final Timer readinessRecomputeScope;
    private final AtomicLong unmetReadyCandidates = new AtomicLong(0);

    public SchedulerMetrics(MeterRegistry registry, JdbcTemplate jdbc) {
        this.registry = registry;
        this.jdbc = jdbc;

        this.dispatchLatency = Timer.builder("scheduler.dispatch.latency")
                .description("Instance runnable -> DISPATCHED latency")
                .publishPercentiles(0.5, 0.95, 0.99, 0.999)
                .publishPercentileHistogram(true)
                .sla(Duration.ofMillis(100), Duration.ofSeconds(1), Duration.ofSeconds(5))
                .register(registry);

        this.deliveryLatency = Timer.builder("scheduler.dispatch.latency.delivery")
                .description("DISPATCHED -> RUNNING delivery latency")
                .publishPercentiles(0.5, 0.95, 0.99, 0.999)
                .publishPercentileHistogram(true)
                .sla(Duration.ofMillis(500), Duration.ofSeconds(2), Duration.ofSeconds(10))
                .register(registry);

        this.roundDuration = Timer.builder("scheduler.round.duration")
                .description("Single schedule round wall-clock duration")
                .publishPercentileHistogram(true)
                .register(registry);

        this.taskDuration = Timer.builder("scheduler.task.duration")
                .description("Task execution wall-clock duration")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram(true)
                .register(registry);

        this.claimRounds = Counter.builder("scheduler.claim.rounds")
                .description("Total claim rounds (event + poll driven)")
                .register(registry);

        this.emptyClaims = Counter.builder("scheduler.claim.empty")
                .description("Empty claim rounds (no runnable instance found)")
                .register(registry);

        this.wakeEvent = Counter.builder("scheduler.wake.source")
                .tag("source", "event")
                .register(registry);

        this.wakePoll = Counter.builder("scheduler.wake.source")
                .tag("source", "poll")
                .register(registry);

        this.dispatchCount = Counter.builder("scheduler.dispatch.count")
                .description("Total task instance dispatches")
                .register(registry);

        this.leaseReclaims = Counter.builder("scheduler.lease.reclaim")
                .description("Lease expiry reclaims (WORKER_LOST / WORKER_RESTART)")
                .register(registry);

        this.cronTriggerLatency = Timer.builder("dw.cron.trigger.latency")
                .description("Cron scheduled fire time -> instance created latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram(true)
                .sla(Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(2))
                .register(registry);

        this.cronMisfireFireOnce = Counter.builder("dw.cron.misfire.count")
                .tag("policy", "fire_once")
                .description("Overdue cron points compensated by firing once")
                .register(registry);

        this.cronMisfireSkip = Counter.builder("dw.cron.misfire.count")
                .tag("policy", "skip")
                .description("Overdue cron points skipped (base advanced only)")
                .register(registry);

        // 045 cron 并行化：fireArm(同步去重+入队)/fireExecute(worker 物化)解耦 + 队列背压 + 补偿器
        this.fireExecuteLatency = Timer.builder("dw.cron.fire.execute.latency")
                .description("fireExecute instance materialization latency (worker pool)")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram(true)
                .register(registry);
        this.fireArmLatency = Timer.builder("dw.cron.fire.arm.latency")
                .description("fireArm sync dedup + enqueue latency (timer thread)")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram(true)
                .register(registry);
        this.fireQueueFull = Counter.builder("dw.cron.fire.queue.full.count")
                .description("Times fireQueue.offer timed out and fell back to sync execute (backpressure)")
                .register(registry);
        this.reconcileReplayed = Counter.builder("dw.cron.reconcile.count")
                .tag("outcome", "replayed")
                .description("Reconciler replayed a lost fire (instance was missing, created)")
                .register(registry);
        this.reconcileSkipped = Counter.builder("dw.cron.reconcile.count")
                .tag("outcome", "skipped")
                .description("Reconciler skipped (instance already existed, idempotent)")
                .register(registry);
        this.reconcileDead = Counter.builder("dw.cron.reconcile.count")
                .tag("outcome", "dead")
                .description("Reconciler marked fire DEAD (timeout exceeded, gave up)")
                .register(registry);
        // 046 dispatch 并行化：dispatchExecutor 有界队列满 → 降级同步下发(背压信号)
        this.dispatchQueueFull = Counter.builder("dw.dispatch.queue.full.count")
                .description("046: Times dispatchQueue rejected and fell back to sync dispatch (backpressure)")
                .register(registry);
        this.claimExtraWindow = Counter.builder("dw.claim.window.extra.count")
                .description("049-closure: Extra claim windows scanned past a blocked prefix (starvation mitigation triggered)")
                .register(registry);
        this.staleDispatchSkip = Counter.builder("dw.dispatch.stale.skip.count")
                .description("049-closure: Queued dispatch commands dropped because instance was reaped/redispatched (double-run guard)")
                .register(registry);

        // 051 就绪态物化指标（只增不改，不可变约定）
        this.readinessSignalLag = Timer.builder("dw.readiness.signal.lag")
                .description("051: Signal created -> downstream unmet_deps=0 latency (p99<3s target)")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram(true)
                .sla(Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofSeconds(10))
                .register(registry);
        this.readinessMaintainBatch = Counter.builder("dw.readiness.maintain.batch")
                .description("051: Maintainer per-round signal + downstream count")
                .register(registry);
        Gauge.builder("dw.readiness.signal.pending", readinessSignalPending, AtomicLong::doubleValue)
                .description("051: Unprocessed readiness_signal backlog depth")
                .register(registry);
        this.readinessDriftCorrected = Counter.builder("dw.readiness.drift.corrected")
                .description("051: Reconciler detected and healed unmet_deps drift count")
                .register(registry);
        this.readinessRecomputeScope = Timer.builder("dw.readiness.recompute.scope")
                .description("051: Single recompute downstream D size (fan-out width)")
                .publishPercentileHistogram(true)
                .register(registry);
        Gauge.builder("dw.readiness.unmet.ready.candidates", unmetReadyCandidates, AtomicLong::doubleValue)
                .description("051: Claim candidates with unmet_deps=0 per round")
                .register(registry);

        Gauge.builder("scheduler.queue.depth", queueDepth, AtomicLong::doubleValue)
                .description("Current WAITING queue depth")
                .register(registry);

        Gauge.builder("scheduler.queue.oldest.age.seconds", oldestAgeSeconds, AtomicLong::doubleValue)
                .description("Age of oldest WAITING instance in seconds")
                .register(registry);

        Gauge.builder("scheduler.slot.utilization", slotUtilization, v -> v.doubleValue() / 1000.0)
                .description("Global slot utilization ratio (0.0-1.0)")
                .register(registry);

        Gauge.builder("scheduler.slot.fragmentation", slotFragmentation, v -> v.doubleValue() / 1000.0)
                .description("Fragmentation: free slots but nothing dispatchable (0.0-1.0)")
                .register(registry);

        Gauge.builder("scheduler.log.stream.backlog", logStreamBacklog, AtomicLong::doubleValue)
                .description("Log stream backlog line count")
                .register(registry);

        Gauge.builder("scheduler.sse.connections", sseConnections, AtomicLong::doubleValue)
                .description("Active SSE connections")
                .register(registry);

        Gauge.builder("dw.cron.shard.workflows", shardWorkflows, AtomicLong::doubleValue)
                .description("Number of cron workflows this master is responsible for (shard)")
                .register(registry);

        Gauge.builder("dw.cron.window.size", cronWindowSize, AtomicLong::doubleValue)
                .description("Current pre-read window armed point count (this master / this shard)")
                .register(registry);
        Gauge.builder("dw.cron.fire.queue.size", fireQueueSize, AtomicLong::doubleValue)
                .description("045: Current cron fire queue depth (pending FireTasks, backpressure signal)")
                .register(registry);
        Gauge.builder("dw.dispatch.queue.size", dispatchQueueSize, AtomicLong::doubleValue)
                .description("046: Current dispatch executor queue depth (pending DispatchCommands, backpressure)")
                .register(registry);

        log.info("[SchedulerMetrics] 调度指标已注册（Micrometer + actuator /prometheus）");
    }

    // ─── 第 1 层 API ─────────────────────────────────────

    public void recordDispatchLatency(Duration d) {
        dispatchLatency.record(d);
    }

    public void recordDeliveryLatency(Duration d) {
        deliveryLatency.record(d);
    }

    /** cron 触发点 → 实例创建延迟（FR-002 / SC-001 / SC-005）。 */
    public void recordCronTriggerLatency(Duration d) {
        cronTriggerLatency.record(d.isNegative() ? Duration.ZERO : d);
    }

    /** misfire 补偿计数（SC-003）：fire_once=补触发一次，skip=仅推进基准。 */
    public void markCronMisfire(boolean fireOnce) {
        if (fireOnce) {
            cronMisfireFireOnce.increment();
        } else {
            cronMisfireSkip.increment();
        }
    }

    /** 本 master 负责的 cron 工作流数（分片模式下 SC-007）。 */
    public void setShardWorkflows(long count) {
        shardWorkflows.set(count);
    }

    /** 当前预读窗口内已装载的点数（FR-014）。 */
    public void setCronWindowSize(long count) {
        cronWindowSize.set(count);
    }

    // ─── 045 cron 并行化 API ──────────────────────────────

    /** 触发队列当前深度（gauge，背压信号）。 */
    public void setFireQueueSize(long size) {
        fireQueueSize.set(size);
    }

    /** 满队列降级计数（>0 表示 worker 跟不上，触发背压传导）。 */
    public void markQueueFull() {
        fireQueueFull.increment();
    }

    /** fireExecute 物化耗时（worker 池）。 */
    public void recordFireExecuteLatency(Duration d) {
        fireExecuteLatency.record(d.isNegative() ? Duration.ZERO : d);
    }

    /** fireArm 同步去重+入队耗时（timer 线程）。 */
    public void recordFireArmLatency(Duration d) {
        fireArmLatency.record(d.isNegative() ? Duration.ZERO : d);
    }

    // ─── 046 dispatch 并行化 API ──────────────────────────────

    /** dispatchExecutor 当前队列深度（gauge,背压信号;由 SchedulerKernel 每轮 claimRound 设置）。 */
    public void setDispatchQueueSize(long size) {
        dispatchQueueSize.set(size);
    }

    /** dispatchQueue 满 → 降级同步下发计数（>0 表示 dispatchExecutor 跟不上 claim,触发背压）。 */
    public void markDispatchQueueFull() {
        dispatchQueueFull.increment();
    }

    public void markClaimExtraWindow() {
        claimExtraWindow.increment();
    }

    public void markStaleDispatchSkip() {
        staleDispatchSkip.increment();
    }

    // ─── 051 就绪态物化 API ──────────────────────────────

    /** 满足方终态→下游变就绪的滞后。 */
    public void recordReadinessSignalLag(Duration d) {
        readinessSignalLag.record(d.isNegative() ? Duration.ZERO : d);
    }

    /** Maintainer 每轮处理下游数。 */
    public void markReadinessMaintainBatch(long count) {
        readinessMaintainBatch.increment(count);
    }

    /** 未处理信号积压深度（gauge）。 */
    public void setReadinessSignalPending(long count) {
        readinessSignalPending.set(count);
    }

    /** Reconciler 检出自愈漂移数。 */
    public void markReadinessDriftCorrected(long count) {
        readinessDriftCorrected.increment(count);
    }

    /** 单次重算的下游 D 规模。 */
    public void recordReadinessRecomputeScope(int size) {
        readinessRecomputeScope.record(Duration.ofMillis(size));
    }

    /** 认领时 unmet_deps=0 候选量。 */
    public void setUnmetReadyCandidates(long count) {
        unmetReadyCandidates.set(count);
    }

    /** reconciler 补偿成功（instance 之前缺失，已创建）。 */
    public void markReconcileReplayed() {
        reconcileReplayed.increment();
    }

    /** reconciler 幂等跳过（instance 已存在）。 */
    public void markReconcileSkipped() {
        reconcileSkipped.increment();
    }

    /** reconciler 标 DEAD（超时放弃）。 */
    public void markReconcileDead() {
        reconcileDead.increment();
    }

    public Timer.Sample startRound() {
        return Timer.start(registry);
    }

    public void endRound(Timer.Sample sample) {
        sample.stop(roundDuration);
        claimRounds.increment();
    }

    public void markEmptyClaim() {
        emptyClaims.increment();
    }

    public void markWakeEvent() {
        wakeEvent.increment();
    }

    public void markWakePoll() {
        wakePoll.increment();
    }

    public void markDispatch() {
        dispatchCount.increment();
    }

    public void markDispatches(int count) {
        dispatchCount.increment(count);
    }

    public void refreshQueueDepth() {
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM task_instance WHERE state='WAITING' AND deleted=0", Integer.class);
            queueDepth.set(count != null ? count : 0);
        } catch (Exception e) {
            // 指标静默吞错
        }
    }

    public void refreshOldestAge() {
        try {
            Long age = jdbc.queryForObject(
                    "SELECT (EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) "
                            + "- EXTRACT(EPOCH FROM MIN(updated_at)))::BIGINT "
                            + "FROM task_instance WHERE state='WAITING' AND deleted=0",
                    Long.class);
            oldestAgeSeconds.set(age != null ? age : 0);
        } catch (Exception e) {
            // 静默吞错
        }
    }

    // ─── 第 2 层 API ─────────────────────────────────────

    public Timer.Sample startTask() {
        return Timer.start(registry);
    }

    public void endTask(Timer.Sample sample, String outcome, Long taskDefId) {
        sample.stop(taskDuration);
        registry.counter("scheduler.task.completed", "outcome",
                outcome != null ? outcome : "UNKNOWN").increment();
        if (taskDefId != null) {
            registry.counter("scheduler.task.per.def",
                    "task_id", String.valueOf(taskDefId),
                    "outcome", outcome != null ? outcome : "UNKNOWN").increment();
        }
    }

    public void recordTaskCompletion(Duration d, String outcome, Long taskDefId) {
        taskDuration.record(d);
        registry.counter("scheduler.task.completed", "outcome",
                outcome != null ? outcome : "UNKNOWN").increment();
        if (taskDefId != null) {
            registry.counter("scheduler.task.per.def",
                    "task_id", String.valueOf(taskDefId),
                    "outcome", outcome != null ? outcome : "UNKNOWN").increment();
        }
    }

    public void markLeaseReclaim() {
        leaseReclaims.increment();
    }

    public void refreshSlotUtilization() {
        try {
            var row = jdbc.queryForMap(
                    "SELECT "
                            + "COALESCE((SELECT COUNT(*) FROM task_instance "
                            + "  WHERE state IN ('DISPATCHED','RUNNING') AND deleted=0), 0) AS used, "
                            + "COALESCE((SELECT SUM(max_concurrent_tasks) FROM worker_nodes "
                            + "  WHERE status='ONLINE' AND deleted=0), 0) AS total");
            long used = ((Number) row.get("USED")).longValue();
            long total = ((Number) row.get("TOTAL")).longValue();
            slotUtilization.set(total > 0 ? used * 1000 / total : 0);
        } catch (Exception e) {
            // 静默吞错
        }
    }

    public void refreshFragmentation() {
        try {
            var row = jdbc.queryForMap(
                    "SELECT "
                            + "COALESCE((SELECT SUM(max_concurrent_tasks) FROM worker_nodes "
                            + "  WHERE status='ONLINE' AND deleted=0), 0) "
                            + "- COALESCE((SELECT COUNT(*) FROM task_instance "
                            + "  WHERE state IN ('DISPATCHED','RUNNING') AND deleted=0), 0) AS free, "
                            + "CASE WHEN EXISTS (SELECT 1 FROM task_instance "
                            + "  WHERE state='WAITING' AND run_mode='NORMAL' AND deleted=0) THEN 1 ELSE 0 END AS hasWait");
            long free = ((Number) row.get("FREE")).longValue();
            int hasWait = ((Number) row.get("HASWAIT")).intValue();
            slotFragmentation.set(free > 0 && hasWait == 0 ? 1000 : 0);
        } catch (Exception e) {
            // 静默吞错
        }
    }

    // ─── 第 3 层 API（Phase 4 管道落成后激活） ──────────

    public void setLogStreamBacklog(long lines) {
        logStreamBacklog.set(lines);
    }

    public void setSseConnections(long count) {
        sseConnections.set(count);
    }

    // ─── 聚合查询（供 /api/ops/metrics） ──────────────

    /**
     * 返回四层指标当前快照，供前端看板与告警模块消费。
     * 百分位延迟通过 Micrometer publishPercentiles 注册的百分位 Gauge 读取
     * （命名为 {@code <metric>.percentile}，tag {@code phi=0.5/0.99/...})。
     */
    public MetricsSnapshot snapshot() {
        MetricsSnapshot s = new MetricsSnapshot();

        // 第 1 层: counters + gauges (Timer 均值自算)
        s.dispatchLatencyMean = dispatchLatency.count() > 0
                ? dispatchLatency.totalTime(TimeUnit.MILLISECONDS) / (double) dispatchLatency.count() : 0;
        s.dispatchLatencyCount = dispatchLatency.count();
        s.deliveryLatencyMean = deliveryLatency.count() > 0
                ? deliveryLatency.totalTime(TimeUnit.MILLISECONDS) / (double) deliveryLatency.count() : 0;
        s.deliveryLatencyCount = deliveryLatency.count();
        s.queueDepth = (int) queueDepth.get();
        s.oldestAgeSeconds = oldestAgeSeconds.get();
        s.totalClaimRounds = (long) claimRounds.count();
        s.emptyClaimRounds = (long) emptyClaims.count();
        s.wakeEvents = (long) wakeEvent.count();
        s.wakePolls = (long) wakePoll.count();
        s.totalDispatches = (long) dispatchCount.count();
        s.roundDurationMean = roundDuration.count() > 0
                ? roundDuration.totalTime(TimeUnit.MILLISECONDS) / (double) roundDuration.count() : 0;

        // 百分位延迟（从 Micrometer publishPercentiles Gauge 读取，单位 ms）
        s.dispatchLatencyP50 = timerPercentile("scheduler.dispatch.latency", 0.5);
        s.dispatchLatencyP99 = timerPercentile("scheduler.dispatch.latency", 0.99);
        s.dispatchLatencyP999 = timerPercentile("scheduler.dispatch.latency", 0.999);
        s.deliveryLatencyP50 = timerPercentile("scheduler.dispatch.latency.delivery", 0.5);
        s.deliveryLatencyP99 = timerPercentile("scheduler.dispatch.latency.delivery", 0.99);
        s.deliveryLatencyP999 = timerPercentile("scheduler.dispatch.latency.delivery", 0.999);
        s.taskDurationP50 = timerPercentile("scheduler.task.duration", 0.5);
        s.taskDurationP99 = timerPercentile("scheduler.task.duration", 0.99);

        // 第 2 层
        s.slotUtilization = slotUtilization.doubleValue() / 1000.0;
        s.slotFragmentation = slotFragmentation.doubleValue() / 1000.0;
        s.taskDurationMean = taskDuration.count() > 0
                ? taskDuration.totalTime(TimeUnit.MILLISECONDS) / (double) taskDuration.count() : 0;
        s.taskCompletedCount = taskDuration.count();
        s.leaseReclaims = (long) leaseReclaims.count();

        // 第 3 层
        s.logStreamBacklog = logStreamBacklog.get();
        s.sseConnections = sseConnections.get();

        // 051 就绪态物化
        s.readinessSignalLagMean = readinessSignalLag.count() > 0
                ? readinessSignalLag.totalTime(TimeUnit.MILLISECONDS) / (double) readinessSignalLag.count() : 0;
        s.readinessSignalLagCount = readinessSignalLag.count();
        s.readinessSignalLagP50 = timerPercentile("dw.readiness.signal.lag", 0.5);
        s.readinessSignalLagP99 = timerPercentile("dw.readiness.signal.lag", 0.99);
        s.readinessSignalPending = readinessSignalPending.get();
        s.readinessDriftCorrected = (long) readinessDriftCorrected.count();
        s.readinessRecomputeScopeMean = readinessRecomputeScope.count() > 0
                ? readinessRecomputeScope.totalTime(TimeUnit.MILLISECONDS) / (double) readinessRecomputeScope.count() : 0;
        s.unmetReadyCandidates = unmetReadyCandidates.get();
        s.claimExtraWindow = (long) claimExtraWindow.count();
        s.staleDispatchSkip = (long) staleDispatchSkip.count();

        return s;
    }

    /**
     * 从 Micrometer 读取已发布的百分位 Gauge 值（ms）。
     * Micrometer 通过 {@code publishPercentiles} 在 registry 中注册额外 Gauge，
     * 命名为 {@code <metricName>.percentile}，tag key 可能是 {@code phi} 或 {@code percentile}。
     *
     * @param metricName Timer 的基础名称（如 {@code scheduler.dispatch.latency})
     * @param phi        百分位（如 0.5, 0.99, 0.999）
     * @return 百分位值（ms），若无数据返回 0
     */
    private double timerPercentile(String metricName, double phi) {
        String phiStr = String.valueOf(phi);
        // Micrometer 1.x/2.x 主要使用 phi tag
        Double val = registry.find(metricName + ".percentile")
                .tag("phi", phiStr)
                .gauges().stream()
                .findFirst()
                .map(Gauge::value)
                .orElse(null);
        if (val != null) return val;

        // 回退：某些 registry 实现可能用 percentile tag
        return registry.find(metricName + ".percentile")
                .tag("percentile", phiStr)
                .gauges().stream()
                .findFirst()
                .map(Gauge::value)
                .orElse(0.0);
    }

    public static final class MetricsSnapshot {
        // 第 1 层：调度性能
        public double dispatchLatencyMean;
        public long dispatchLatencyCount;
        public double deliveryLatencyMean;
        public long deliveryLatencyCount;
        public int queueDepth;
        public long oldestAgeSeconds;
        public long totalClaimRounds;
        public long emptyClaimRounds;
        public long wakeEvents;
        public long wakePolls;
        public long totalDispatches;
        public double roundDurationMean;

        // 百分位延迟（ms）：p50/p99/p999
        public double dispatchLatencyP50;
        public double dispatchLatencyP99;
        public double dispatchLatencyP999;
        public double deliveryLatencyP50;
        public double deliveryLatencyP99;
        public double deliveryLatencyP999;
        public double taskDurationP50;
        public double taskDurationP99;

        // 第 2 层：资源与执行
        public double slotUtilization;
        public double slotFragmentation;
        public double taskDurationMean;
        public long taskCompletedCount;
        public long leaseReclaims;

        // 第 3 层：管道健康
        public long logStreamBacklog;
        public long sseConnections;

        // 051 就绪态物化
        public double readinessSignalLagMean;
        public long readinessSignalLagCount;
        public double readinessSignalLagP50;
        public double readinessSignalLagP99;
        public long readinessSignalPending;
        public long readinessDriftCorrected;
        public double readinessRecomputeScopeMean;
        public long unmetReadyCandidates;
        public long claimExtraWindow;
        public long staleDispatchSkip;
    }
}
