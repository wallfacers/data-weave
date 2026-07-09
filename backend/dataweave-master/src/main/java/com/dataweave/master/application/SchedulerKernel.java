package com.dataweave.master.application;

import com.dataweave.master.application.SchedulingPolicy.Candidate;
import com.dataweave.master.application.SchedulingPolicy.NodeLoad;
import com.dataweave.master.application.TaskExecutionGateway.DispatchCommand;
import com.dataweave.master.domain.EventBus;
import com.dataweave.master.domain.InstanceStates;
import com.dataweave.master.i18n.Messages;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 调度内核（design D1/D2/D3，task 2.2/2.3）：多 master 对等、事件驱动为主 + 轮询兜底、死锁防御不变量。
 *
 * <p>一轮调度 {@link #scheduleOnce()}：
 * <ol>
 *   <li><b>认领（短事务）</b>：{@code SELECT … FOR UPDATE SKIP LOCKED} 锁取可运行的 WAITING 实例
 *       （上游 SUCCESS 才可运行——「等待不占资源」），TEST 用预留槽、NORMAL 用非预留槽，按
 *       {@link SchedulingPolicy} 有效优先级择机；选定节点后 CAS 置 DISPATCHED 落租约，提交。</li>
 *   <li><b>下发（事务外）</b>：对已 DISPATCHED 的实例调用 {@link TaskExecutionGateway}；失败 CAS 回 WAITING 重派。</li>
 * </ol>
 * 触发：兜底 {@code @Scheduled} 轮询 + 订阅 {@link InstanceStates#WAKE_CHANNEL} 唤醒（提交/完成/槽位释放即触发）。
 */
@Service
public class SchedulerKernel {

    private static final Logger log = LoggerFactory.getLogger(SchedulerKernel.class);

    private final JdbcTemplate jdbc;
    private final InstanceStateMachine stateMachine;
    private final SlotManager slotManager;
    private final SchedulingPolicy policy;
    private final TaskExecutionGateway gateway;
    private final EventBus eventBus;
    private final PreemptionService preemptionService;
    private final SchedulerMetrics metrics;
    private final ParallelDispatcher dispatcher;
    private final ScheduleParamResolver paramResolver;
    private final Messages messages;
    private final TransactionTemplate txTemplate;
    private final int claimBatchSize;
    private final long leaseSeconds;
    private final int claimCandidateSize;  // 049:放大候选(去 NOT EXISTS 后 Java filter 的余量)；051 物化后缩小
    private final int claimMaxWindows;     // 049-收尾:防饿死游标翻窗上限(有界护栏)；051 物化后废弃
    private final boolean readinessMaterialized;  // 051:门控 unmet_deps=0 过滤

    // 版本键缓存上限:满则整体清空重热(条目随发布版本数缓慢增长,防长期运行无界;不引缓存库)
    private static final int VERSION_CACHE_MAX = 10_000;

    // 串行化一轮调度（CAS 已保正确，串行仅去重避免空抢）；运行中再来唤醒则置 rerun，结束后补跑。
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean rerun = new AtomicBoolean(false);
    // 046 N+1 消除：content/params 按版本缓存（静态，版本冻结可跨轮复用；cron 重复触发命中率高）。
    // 消除 assign() 每行查 task_def_version / task_def 的 N+1（50 行 × 2-4 查询 → 首轮后全命中）。
    private final java.util.concurrent.ConcurrentHashMap<String, String> contentCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, String> paramsCache = new java.util.concurrent.ConcurrentHashMap<>();

    public SchedulerKernel(JdbcTemplate jdbc,
                           InstanceStateMachine stateMachine,
                           SlotManager slotManager,
                           SchedulingPolicy policy,
                           TaskExecutionGateway gateway,
                           EventBus eventBus,
                           PreemptionService preemptionService,
                           SchedulerMetrics metrics,
                           ParallelDispatcher dispatcher,
                           ScheduleParamResolver paramResolver,
                           Messages messages,
                           PlatformTransactionManager txManager,
                           @Value("${scheduler.claim-batch-size:50}") int claimBatchSize,
                           @Value("${scheduler.lease-seconds:120}") long leaseSeconds,
                           @Value("${scheduler.claim-candidate-size:200}") int claimCandidateSize,
                           @Value("${scheduler.claim-max-windows:5}") int claimMaxWindows,
                           @Value("${scheduler.readiness.materialized:false}") boolean readinessMaterialized) {
        this.jdbc = jdbc;
        this.stateMachine = stateMachine;
        this.slotManager = slotManager;
        this.policy = policy;
        this.gateway = gateway;
        this.eventBus = eventBus;
        this.preemptionService = preemptionService;
        this.metrics = metrics;
        this.dispatcher = dispatcher;
        this.paramResolver = paramResolver;
        this.messages = messages;
        this.txTemplate = new TransactionTemplate(txManager);
        this.claimBatchSize = claimBatchSize;
        this.leaseSeconds = leaseSeconds;
        this.claimCandidateSize = claimCandidateSize;
        this.claimMaxWindows = Math.max(1, claimMaxWindows);
        this.readinessMaterialized = readinessMaterialized;
    }

    @PostConstruct
    void subscribeWake() {
        eventBus.subscribe(InstanceStates.WAKE_CHANNEL, msg -> {
            metrics.markWakeEvent();
            scheduleOnce();
        });
    }

    /** 兜底轮询：捞事件丢失与 master 宕机留下的漏网之鱼。 */
    @Scheduled(fixedRateString = "${scheduler.poll-interval-ms:5000}")
    public void poll() {
        metrics.markWakePoll();
        scheduleOnce();
    }

    /** 触发一轮调度（去重串行）。 */
    public void scheduleOnce() {
        if (!running.compareAndSet(false, true)) {
            rerun.set(true);
            return;
        }
        try {
            do {
                rerun.set(false);
                runRound();
            } while (rerun.get());
        } finally {
            running.set(false);
        }
    }

    private void runRound() {
        Timer.Sample roundSample = metrics.startRound();
        List<DispatchCommand> dispatched;
        // DISPATCHED 事件收集:事务提交后才发布(Row 已带 workflowInstanceId,免逐条回查;
        // 事务内先发会把未提交状态推给 SSE,回滚则成假事件)。
        List<InstanceStateMachine.DispatchedEvent> events = new ArrayList<>();
        try {
            dispatched = txTemplate.execute(status -> claimAndMark(events));
        } catch (Exception e) {
            log.warn("[Scheduler] 认领事务失败：{}", e.getMessage());
            metrics.endRound(roundSample);
            return;
        }
        stateMachine.publishDispatchedEvents(events);
        if (dispatched == null || dispatched.isEmpty()) {
            metrics.markEmptyClaim();
            metrics.endRound(roundSample);
            // 无可下发：若有积压的高优待调度且无空槽，尝试软抢占腾位，成功则补跑一轮。
            if (preemptionService.preemptOneForWaitingHighPriority()) {
                rerun.set(true);
            }
            return;
        }
        metrics.markDispatches(dispatched.size());
        // 046:事务外异步下发(fire-and-forget)—— submit 到 dispatchExecutor 立即返回,claim 线程不等 dispatch,
        // 下一轮 claim 可马上开始(解除 claim↔dispatch 串行)。失败 casRequeue 回 WAITING 重派。
        // 出队守卫:租约自 claim 时刻起算,命令在队列滞留超租约会被 LeaseReaper 回收重派(attempt+1);
        // worker 幂等键含 attempt 拦不住旧命令 → 发 HTTP 前核对仍是当前派单,否则丢弃防同实例双跑。
        dispatcher.dispatchAllAsync(dispatched, cmd -> {
            if (!stateMachine.isCurrentDispatch(cmd.taskInstanceId(), cmd.attempt())) {
                metrics.markStaleDispatchSkip();
                log.info("[Scheduler] 实例 {} attempt={} 已非当前派单(滞留期间被回收/重派)，跳过下发",
                        cmd.taskInstanceId(), cmd.attempt());
                return;
            }
            gateway.dispatch(cmd);
        }, (cmd, err) -> {
            log.warn("[Scheduler] 下发实例 {} 失败，回退 WAITING：{}", cmd.taskInstanceId(), err.getMessage());
            stateMachine.casRequeue(cmd.taskInstanceId(), InstanceStates.DISPATCHED);
        });
        metrics.setDispatchQueueSize(dispatcher.queueSize());  // 046:背压观测
        metrics.endRound(roundSample);
    }

    /** 在事务内认领可运行实例、分配节点、CAS 置 DISPATCHED，返回待下发指令。 */
    private List<DispatchCommand> claimAndMark(List<InstanceStateMachine.DispatchedEvent> events) {
        List<NodeSlot> nodes = new ArrayList<>();
        for (NodeLoad nl : slotManager.snapshotOnline()) {
            int reserved = nl.node().getReservedTestSlots() == null ? 0 : nl.node().getReservedTestSlots();
            nodes.add(new NodeSlot(nl.node().getNodeCode(), nl.used(), nl.capacity(), reserved, nl.node().getLoadAvg()));
        }
        if (nodes.stream().noneMatch(n -> n.free() > 0)) {
            return List.of();  // 无空槽：work-conserving 下也无可派，等唤醒。
        }

        List<DispatchCommand> out = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        // TEST 先行（高优 + 可用全部空槽，含预留；无 Java 就绪门 → 无饿死问题，单窗即可）
        List<Row> tests = selectRunnable(RunMode.TEST, null);
        assign(tests, nodes, true, now, out, events);

        // 051:就绪态物化——候选即就绪(unmet_deps=0 已入 SQL),无需 Java 就绪门 + 窗口游标。
        // materialized=false 时仍走原 collectReady 路径(灰度兼容)。
        if (readinessMaterialized) {
            List<Row> normals = selectRunnable(RunMode.NORMAL, null);
            normals.addAll(selectRunnable(RunMode.BACKFILL, null));
            if (!normals.isEmpty()) {
                metrics.setUnmetReadyCandidates(normals.size());
                List<Row> sorted = normals.stream()
                        .sorted(Comparator.comparingInt(r -> policy.effectivePriority(toCandidate(r), now)))
                        .toList();
                assign(sorted, nodes, false, now, out, events);
            }
        } else {
            // 灰度路径：物化未完成时走原 collectReady（Java 就绪门 + 窗口游标）
            List<Row> ready = new ArrayList<>();
            collectReady(RunMode.NORMAL, ready);
            collectReady(RunMode.BACKFILL, ready);
            if (!ready.isEmpty()) {
                List<Row> normals = ready.stream()
                        .sorted(Comparator.comparingInt(r -> policy.effectivePriority(toCandidate(r), now)))
                        .toList();
                assign(normals, nodes, false, now, out, events);
            }
        }

        return out;
    }

    /**
     * 049-收尾:按运行模式收集经上游门+跨周期门过滤的就绪候选。首窗无游标(热路径与 049 原形态一致);
     * 就绪量不足 claimBatchSize 且窗口打满(前缀全被未就绪实例占住)时,取窗尾 (updated_at,id) 为游标
     * 续扫下一窗,至多 {@link #claimMaxWindows} 窗——修复窗口饿死,同时护栏 round 时长
     * (最坏 claimMaxWindows × 窗口成本,R9 实测单窗 ~5ms)。彻底解需就绪态物化(unmet 计数),留结构演进。
     */
    private void collectReady(RunMode mode, List<Row> ready) {
        int limit = mode == RunMode.NORMAL ? claimCandidateSize : claimBatchSize;
        Row cursor = null;
        for (int w = 0; w < claimMaxWindows; w++) {
            if (w > 0) {
                metrics.markClaimExtraWindow();  // 饿死缓解触发信号(前缀被未就绪实例占满)
            }
            List<Row> cand = selectRunnable(mode, cursor);
            if (cand.isEmpty()) {
                return;
            }
            Set<UUID> upstreamReady = batchUpstreamReady(cand);   // 049:批量上游就绪(替 NOT EXISTS)
            Set<UUID> crossCycleReady = batchCrossCycleReady(cand);  // 048:批量跨周期判定
            for (Row r : cand) {
                if (upstreamReady.contains(r.id) && crossCycleReady.contains(r.id)) {
                    ready.add(r);
                }
            }
            if (ready.size() >= claimBatchSize || cand.size() < limit) {
                return;  // 就绪够一批 / 已扫到队尾
            }
            cursor = cand.get(cand.size() - 1);
        }
    }

    /**
     * 048 重构:三阶段批量 assign(place → 批量 cas → 后处理),消除逐行 casDispatch N+1(③)。
     * 阶段1 place 所有行收集 placements(ns.used++ 乐观占槽)→ 阶段2 单 SQL casDispatchBatch
     * (FOR UPDATE 行锁保护必全成功)→ 阶段3 逐个 resolveContent + out.add(content 失败内部 casFailed)。
     */
    private void assign(List<Row> rows, List<NodeSlot> nodes, boolean test, LocalDateTime now,
                        List<DispatchCommand> out, List<InstanceStateMachine.DispatchedEvent> events) {
        if (rows.isEmpty()) return;
        // 阶段1: place 所有行,收集 placements + 记录 id→Row(后处理需要)
        List<InstanceStateMachine.DispatchPlacement> placements = new ArrayList<>();
        Map<UUID, Row> placementRows = new LinkedHashMap<>();
        for (Row r : rows) {
            List<NodeLoad> avail = new ArrayList<>();
            for (NodeSlot ns : nodes) {
                int free = test ? ns.free() : ns.normalFree();
                if (free > 0) {
                    // NodeLoad.free()==capacity-used，须等于本模式可用槽 free；故 used 取 capacity-free。
                    avail.add(new NodeLoad(stub(ns), ns.capacity - free, ns.capacity));
                }
            }
            policy.place(toCandidate(r), avail).ifPresent(chosen -> {
                String code = chosen.node().getNodeCode();
                NodeSlot ns = find(nodes, code);
                int attempt = (r.attempt == null ? 0 : r.attempt) + 1;
                LocalDateTime lease = now.plusSeconds(leaseSeconds);
                placements.add(new InstanceStateMachine.DispatchPlacement(r.id, code, lease, attempt));
                placementRows.put(r.id, r);
                ns.used++;  // 乐观占槽(批量 cas 必成功,行锁保护)
                // 派单延迟：WAITING 入队时刻（updated_at）→ 派单成功此刻（now）；批量 cas 必成功故在 place 阶段记。
                if (r.waitingSince != null) {
                    metrics.recordDispatchLatency(Duration.between(r.waitingSince, now));
                }
            });
        }
        if (placements.isEmpty()) return;
        // 阶段2: 批量 casDispatch(单 SQL UPDATE FROM VALUES,事务内行锁保护必全成功)
        int updateCount = stateMachine.casDispatchBatch(placements, now);
        // 防御：updateCount < placements.size() 理论不应发生（FOR UPDATE 行锁保护），但若发生则按
        // DB 真实 DISPATCHED 集合过滤，避免对未成功 CAS 的实例构造 DispatchCommand 造成重复下发。
        Set<UUID> dispatchedIds;
        if (updateCount < placements.size()) {
            log.warn("[Scheduler] 批量 casDispatch updateCount={} < placements.size()={}，回查 DISPATCHED 集合过滤",
                    updateCount, placements.size());
            List<UUID> ids = jdbc.query(
                    "SELECT id FROM task_instance WHERE state='DISPATCHED' AND id = ANY(?) AND deleted=0",
                    (rs, n) -> rs.getObject("id", UUID.class),
                    placements.stream().map(InstanceStateMachine.DispatchPlacement::id).toArray(UUID[]::new));
            dispatchedIds = new HashSet<>(ids);
        } else {
            dispatchedIds = null;  // 全成功，免回查开销
        }
        // 阶段3: 后处理(逐个 resolveContent + out.add;content 失败内部已 casFailed,不下发)
        for (InstanceStateMachine.DispatchPlacement p : placements) {
            if (dispatchedIds != null && !dispatchedIds.contains(p.id())) {
                continue;  // 批量 CAS 未命中此行，跳过下发（防重复命令）
            }
            Row r = placementRows.get(p.id());
            String content = resolveContentSafely(r, now);
            if (content == null) {
                continue;  // 占位符解析失败：已 CAS 置 FAILED，不下发
            }
            int timeout = r.timeoutSec != null ? r.timeoutSec : 0;
            // SPARK 任务：从 params_json 提取 _sparkMode/_jarRef/_mainClass 带入下发链（端到端 sparkMode 注入）
            String sparkMode = null, jarRef = null, mainClass = null;
            if ("SPARK".equalsIgnoreCase(r.taskType)) {
                String pj = paramsJsonOf(r);
                sparkMode = jsonStr(pj, "_sparkMode");
                jarRef = jsonStr(pj, "_jarRef");
                mainClass = jsonStr(pj, "_mainClass");
            }
            // 通用引擎任务（FLINK/DATAX/SEATUNNEL）：从 params_json 提取 _flinkMode/_jarRef/_mainClass
            String engineMode = null, engineJarRef = null, engineMainClass = null;
            if ("FLINK".equalsIgnoreCase(r.taskType) || "DATAX".equalsIgnoreCase(r.taskType)
                    || "SEATUNNEL".equalsIgnoreCase(r.taskType)) {
                String pj = paramsJsonOf(r);
                engineMode = jsonStr(pj, "_flinkMode");
                engineJarRef = jsonStr(pj, "_jarRef");
                engineMainClass = jsonStr(pj, "_mainClass");
            }
            out.add(new DispatchCommand(r.id, p.attempt(), p.workerNodeCode(), r.taskId, r.taskVersionNo,
                    r.runMode, r.bizDate, content, timeout, r.taskType, r.datasourceId, r.locale,
                    sparkMode, jarRef, mainClass,
                    engineMode, engineJarRef, engineMainClass));
            // DISPATCHED 事件延迟到事务提交后发布(runRound);content 失败实例已被 casFailed 发 FAILED,不再发 DISPATCHED。
            events.add(new InstanceStateMachine.DispatchedEvent(r.id, r.workflowInstanceId));
        }
    }

    // 仅为 policy.place 提供 nodeCode/loadAvg 的最小 WorkerNode 包装
    private com.dataweave.master.domain.WorkerNode stub(NodeSlot ns) {
        com.dataweave.master.domain.WorkerNode w = new com.dataweave.master.domain.WorkerNode();
        w.setNodeCode(ns.code);
        w.setLoadAvg(ns.loadAvg);
        return w;
    }

    private NodeSlot find(List<NodeSlot> nodes, String code) {
        for (NodeSlot n : nodes) {
            if (n.code.equals(code)) {
                return n;
            }
        }
        return null;
    }

    private Candidate toCandidate(Row r) {
        int declared = r.priority == null ? 5 : r.priority;
        return new Candidate(r.id, declared, r.waitingSince, r.test());
    }

    /** 049:认领候选运行模式(TEST 高优先行 / NORMAL 主流 / BACKFILL 补数据)。等值用索引,避 IN 打破索引。
     *  package-private 供测试(collectReady 游标翻窗,同 Row 先例)。 */
    enum RunMode { TEST, NORMAL, BACKFILL }

    /**
     * 049:认领可运行候选(FOR UPDATE SKIP LOCKED 锁取,事务内)。runMode 等值用 idx_task_instance_claim 索引
     * (避 IN 打破索引:048 R10 EXPLAIN 实测 IN→Seq Scan 244ms vs 等值 Index Scan 0.43ms,566x)。
     * NORMAL/BACKFILL 含 backfill_held + workflow_instance state 门;NOT EXISTS 上游门移出(Java batchUpstreamReady,
     * 避大表 NOT EXISTS 退化 327-493ms)。NORMAL LIMIT 放大 claimCandidateSize(去 NOT EXISTS 后 Java filter 余量)。
     *
     * <p>049-收尾:{@code after} 非空时按 (updated_at,id) 元组游标取下一窗(OR 展开式,H2/PG 双兼容;
     * 排序补 id 决胜键保证游标全序——批量物化的实例常共享同一 updated_at)。同事务内自己已锁的行
     * SKIP LOCKED 不会跳过(自锁可重入),必须靠游标推进。
     */
    private List<Row> selectRunnable(RunMode runMode, Row after) {
        boolean isTest = runMode == RunMode.TEST;
        int limit = runMode == RunMode.NORMAL ? claimCandidateSize : claimBatchSize;
        List<Object> args = new ArrayList<>();
        String sql = "SELECT ti.id, ti.workflow_instance_id, ti.workflow_node_id, ti.task_id, ti.task_version_no, "
                + "ti.content_override, ti.params_override, "
                + "ti.attempt, ti.run_mode, ti.biz_date, ti.updated_at, "
                + "(SELECT wi.priority FROM workflow_instance wi WHERE wi.id=ti.workflow_instance_id) AS wpriority, "
                + "(SELECT td.timeout_sec FROM task_def td WHERE td.id=ti.task_id) AS timeout_sec, "
                + "COALESCE(ti.type_override, (SELECT td.type FROM task_def td WHERE td.id=ti.task_id)) AS task_type, "
                + "(SELECT td.datasource_id FROM task_def td WHERE td.id=ti.task_id) AS datasource_id, "
                + "(SELECT wi.trigger_type FROM workflow_instance wi WHERE wi.id=ti.workflow_instance_id) AS wtrigger, "
                + "(SELECT wi.workflow_id FROM workflow_instance wi WHERE wi.id=ti.workflow_instance_id) AS wfid, "
                + "ti.locale FROM task_instance ti "
                + "WHERE ti.state='WAITING' AND ti.run_mode='" + runMode.name() + "' AND ti.deleted=0 ";
        if (!isTest) {
            // 节流门(backfill-parallelism-throttle):被持有的补数据实例不可认领;由 BackfillPromoter 晋升(held 1→0)。
            // NORMAL 实例 backfill_held 默认 0 不受影响;任务级 frozen 门已退役(ops-center-publish-boundary,改节点级 overlay)。
            sql += "AND COALESCE(ti.backfill_held,0)=0 "
                    + "AND (ti.workflow_instance_id IS NULL OR (SELECT wi.state FROM workflow_instance wi "
                    + "WHERE wi.id=ti.workflow_instance_id) NOT IN ('PAUSED','STOPPED')) ";
            // 051:就绪态物化——unmet_deps=0 直接判定就绪,替代 049 的 Java batchUpstreamReady + batchCrossCycleReady
            if (readinessMaterialized) {
                sql += "AND ti.unmet_deps = 0 ";
            }
        }
        if (after != null) {
            sql += "AND (ti.updated_at > ? OR (ti.updated_at = ? AND ti.id > ?)) ";
            args.add(after.waitingSince);
            args.add(after.waitingSince);
            args.add(after.id);
        }
        sql += "ORDER BY ti.updated_at ASC, ti.id ASC LIMIT " + limit + " FOR UPDATE SKIP LOCKED";
        return jdbc.query(sql, (rs, n) -> {
            Row r = new Row();
            r.id = rs.getObject("id", UUID.class);
            r.workflowInstanceId = rs.getObject("workflow_instance_id", UUID.class);
            r.workflowNodeId = (Long) rs.getObject("workflow_node_id");
            r.taskId = (Long) rs.getObject("task_id");
            r.taskVersionNo = (Integer) rs.getObject("task_version_no");
            r.contentOverride = rs.getString("content_override");
            r.paramsOverride = rs.getString("params_override");
            r.attempt = (Integer) rs.getObject("attempt");
            r.runMode = rs.getString("run_mode");
            r.bizDate = rs.getString("biz_date");
            r.waitingSince = rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toLocalDateTime() : null;
            r.priority = (Integer) rs.getObject("wpriority");
            r.timeoutSec = (Integer) rs.getObject("timeout_sec");
            r.taskType = rs.getString("task_type");
            r.datasourceId = (Long) rs.getObject("datasource_id");
            r.locale = rs.getString("locale");
            r.workflowTrigger = rs.getString("wtrigger");
            r.workflowId = (Long) rs.getObject("wfid");
            return r;
        }, args.toArray());
    }

    /**
     * 跨周期就绪判定（design D4，实现为 Java 层过滤以避开 H2/PG 日期运算方言差异）：
     * 仅周期触发（CRON）实例检查；手动/测试实例忽略跨周期依赖（即席运行不背跨周期包袱）。
     * 本节点每条启用的跨周期依赖（earliest_biz_date 非空=启用），其 depend_node 在 biz_date 按
     * date_offset 偏移后的上一周期实例必须存在 SUCCESS；biz_date<earliest_biz_date 者豁免（首周期 bootstrap）。
     */
    private boolean crossCycleReady(Row r) {
        if (!"CRON".equals(r.workflowTrigger)) {
            return true;
        }
        if (r.workflowInstanceId == null || r.workflowNodeId == null || r.bizDate == null) {
            return true;
        }
        List<DepRow> deps = jdbc.query(
                "SELECT depend_node_id, date_offset, earliest_biz_date FROM workflow_dependency "
                        + "WHERE workflow_id=(SELECT workflow_id FROM workflow_instance WHERE id=?) "
                        + "AND node_id=? AND enabled=1 AND deleted=0 AND earliest_biz_date IS NOT NULL",
                (rs, n) -> new DepRow(
                        (Long) rs.getObject("depend_node_id"),
                        rs.getString("date_offset"),
                        rs.getString("earliest_biz_date")),
                r.workflowInstanceId, r.workflowNodeId);
        for (DepRow d : deps) {
            if (d.earliestBizDate() != null && r.bizDate.compareTo(d.earliestBizDate()) < 0) {
                continue;  // 首周期豁免：biz_date 早于回溯起点，不检查上一周期
            }
            String prevBizDate = offsetBizDate(r.bizDate, d.dateOffset());
            Integer cnt = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM task_instance WHERE workflow_node_id=? AND biz_date=? "
                            + "AND state='SUCCESS' AND deleted=0",
                    Integer.class, d.dependNodeId(), prevBizDate);
            if (cnt == null || cnt == 0) {
                return false;  // 上一周期未 SUCCESS，跨周期未就绪
            }
        }
        return true;
    }

    /**
     * 048:批量跨周期就绪判定(消 N+1 ①②)。CRON 实例一次性查依赖 + 一次性 COUNT,Java 层组装就绪集合。
     * 非 CRON / 缺字段直通就绪(同单行 {@link #crossCycleReady} 语义)。保留首周期豁免。
     */
    private Set<UUID> batchCrossCycleReady(List<Row> normals) {
        Set<UUID> ready = new HashSet<>();
        List<Row> cronRows = new ArrayList<>();
        for (Row r : normals) {
            if (!"CRON".equals(r.workflowTrigger)
                    || r.workflowInstanceId == null || r.workflowNodeId == null
                    || r.bizDate == null || r.workflowId == null) {
                ready.add(r.id);  // 非 CRON / 缺字段 → 直通就绪
            } else {
                cronRows.add(r);
            }
        }
        if (cronRows.isEmpty()) return ready;

        // ① (workflow_id, node_id) 去重 → 批量查依赖
        LinkedHashMap<String, long[]> wfNode = new LinkedHashMap<>();  // key → [workflowId, nodeId]
        for (Row r : cronRows) {
            wfNode.putIfAbsent(r.workflowId + ":" + r.workflowNodeId,
                    new long[]{r.workflowId, r.workflowNodeId});
        }
        StringBuilder depIn = new StringBuilder();
        List<Object> depArgs = new ArrayList<>();
        int i = 0;
        for (String key : wfNode.keySet()) {
            if (i++ > 0) depIn.append(',');
            depIn.append("(?,?)");
            depArgs.add(wfNode.get(key)[0]);
            depArgs.add(wfNode.get(key)[1]);
        }
        Map<String, List<DepRow>> depsByKey = jdbc.query(
                "SELECT workflow_id, node_id, depend_node_id, date_offset, earliest_biz_date "
                        + "FROM workflow_dependency WHERE enabled=1 AND deleted=0 AND earliest_biz_date IS NOT NULL "
                        + "AND (workflow_id, node_id) IN (" + depIn + ")",
                (rs) -> {
                    Map<String, List<DepRow>> m = new HashMap<>();
                    while (rs.next()) {
                        String key = rs.getLong("workflow_id") + ":" + rs.getLong("node_id");
                        m.computeIfAbsent(key, k -> new ArrayList<>()).add(new DepRow(
                                (Long) rs.getObject("depend_node_id"),
                                rs.getString("date_offset"),
                                rs.getString("earliest_biz_date")));
                    }
                    return m;
                }, depArgs.toArray());

        // ② 收集所有 (depend_node_id, prev_biz_date) → 批量 COUNT;无依赖的 cron row 直通就绪
        LinkedHashSet<String> countKeys = new LinkedHashSet<>();
        for (Row r : cronRows) {
            List<DepRow> deps = depsByKey.get(r.workflowId + ":" + r.workflowNodeId);
            if (deps == null) {
                ready.add(r.id);  // 无依赖 → 就绪
                continue;
            }
            for (DepRow d : deps) {
                if (d.earliestBizDate() != null && r.bizDate.compareTo(d.earliestBizDate()) < 0) continue;  // 首周期豁免
                countKeys.add(d.dependNodeId() + ":" + offsetBizDate(r.bizDate, d.dateOffset()));
            }
        }
        Map<String, Integer> successCounts = new HashMap<>();
        if (!countKeys.isEmpty()) {
            StringBuilder cntIn = new StringBuilder();
            List<Object> cntArgs = new ArrayList<>();
            int j = 0;
            for (String ck : countKeys) {
                String[] parts = ck.split(":", 2);
                if (j++ > 0) cntIn.append(',');
                cntIn.append("(?,?)");
                cntArgs.add(Long.parseLong(parts[0]));
                cntArgs.add(parts[1]);
            }
            successCounts = jdbc.query(
                    "SELECT workflow_node_id, biz_date, COUNT(*) FROM task_instance "
                            + "WHERE state='SUCCESS' AND deleted=0 AND (workflow_node_id, biz_date) IN (" + cntIn + ") "
                            + "GROUP BY workflow_node_id, biz_date",
                    (rs) -> {
                        Map<String, Integer> m = new HashMap<>();
                        while (rs.next()) {
                            m.put(rs.getLong("workflow_node_id") + ":" + rs.getString("biz_date"), rs.getInt(3));
                        }
                        return m;
                    }, cntArgs.toArray());
        }

        // ③ 校验每个 CRON row 的所有 dep 就绪(首周期豁免 + prevBizDate COUNT>0)
        for (Row r : cronRows) {
            List<DepRow> deps = depsByKey.get(r.workflowId + ":" + r.workflowNodeId);
            if (deps == null) continue;  // 无依赖已加入 ready
            boolean allReady = true;
            for (DepRow d : deps) {
                if (d.earliestBizDate() != null && r.bizDate.compareTo(d.earliestBizDate()) < 0) continue;  // 首周期豁免
                Integer cnt = successCounts.get(d.dependNodeId() + ":" + offsetBizDate(r.bizDate, d.dateOffset()));
                if (cnt == null || cnt == 0) {
                    allReady = false;
                    break;
                }
            }
            if (allReady) ready.add(r.id);
        }
        return ready;
    }

    /**
     * 049:批量上游就绪判定(替 selectRunnable NOT EXISTS,避大表 NOT EXISTS 退化 327-493ms)。一次性查
     * workflow_edge(to_node_id IN)+ pred task_instance state(SUCCESS/FAILED),Java 层组装就绪集合。
     * 强依赖(strength=STRONG/默认):pred 须 SUCCESS;弱依赖(WEAK):pred 须 SUCCESS 或 FAILED(自然跑完)。
     * 无 edge / 缺 workflowInstanceId 的节点直通就绪(单节点 wf / 单跑实例)。语义同原 NOT EXISTS。
     */
    private Set<UUID> batchUpstreamReady(List<Row> candidates) {
        Set<UUID> ready = new HashSet<>();
        List<Row> checked = new ArrayList<>();
        for (Row r : candidates) {
            if (r.workflowInstanceId == null || r.workflowNodeId == null) {
                ready.add(r.id);  // 单跑实例/缺字段 → 无上游门,直通
            } else {
                checked.add(r);
            }
        }
        if (checked.isEmpty()) return ready;

        // ① 批量查 workflow_edge:to_node_id IN (候选 workflowNodeId)
        Set<Long> toNodeIds = new LinkedHashSet<>();
        for (Row r : checked) toNodeIds.add(r.workflowNodeId);
        StringBuilder edgeIn = new StringBuilder();
        for (int i = 0; i < toNodeIds.size(); i++) edgeIn.append(i == 0 ? "?" : ",?");
        Map<Long, List<Edge>> edgesByTo = jdbc.query(
                "SELECT to_node_id, from_node_id, strength FROM workflow_edge WHERE deleted=0 AND to_node_id IN ("
                        + edgeIn + ")",
                (rs) -> {
                    Map<Long, List<Edge>> m = new HashMap<>();
                    while (rs.next()) {
                        m.computeIfAbsent(rs.getLong("to_node_id"), k -> new ArrayList<>())
                                .add(new Edge(rs.getLong("from_node_id"), rs.getString("strength")));
                    }
                    return m;
                }, toNodeIds.toArray());

        // ② 收集所有 (workflowInstanceId, fromNodeId) → 批量查 pred state;无 edge 的候选直通就绪
        LinkedHashSet<String> predKeys = new LinkedHashSet<>();
        Map<String, UUID> predKeyToWi = new HashMap<>();
        Map<String, Long> predKeyToNode = new HashMap<>();
        for (Row r : checked) {
            List<Edge> edges = edgesByTo.get(r.workflowNodeId);
            if (edges == null) {
                ready.add(r.id);  // 无上游 edge → 直通就绪(单节点 wf)
                continue;
            }
            for (Edge e : edges) {
                String key = r.workflowInstanceId + ":" + e.fromNodeId();
                predKeys.add(key);
                predKeyToWi.putIfAbsent(key, r.workflowInstanceId);
                predKeyToNode.putIfAbsent(key, e.fromNodeId());
            }
        }
        Map<String, Set<String>> predStates = new HashMap<>();
        if (!predKeys.isEmpty()) {
            StringBuilder predIn = new StringBuilder();
            List<Object> predArgs = new ArrayList<>();
            int i = 0;
            for (String key : predKeys) {
                if (i++ > 0) predIn.append(',');
                predIn.append("(?,?)");
                predArgs.add(predKeyToWi.get(key));
                predArgs.add(predKeyToNode.get(key));
            }
            predStates = jdbc.query(
                    "SELECT workflow_instance_id, workflow_node_id, state FROM task_instance "
                            + "WHERE deleted=0 AND state IN ('SUCCESS','FAILED') "
                            + "AND (workflow_instance_id, workflow_node_id) IN (" + predIn + ")",
                    (rs) -> {
                        Map<String, Set<String>> m = new HashMap<>();
                        while (rs.next()) {
                            m.computeIfAbsent(rs.getObject("workflow_instance_id") + ":" + rs.getLong("workflow_node_id"),
                                            k -> new HashSet<>()).add(rs.getString("state"));
                        }
                        return m;
                    }, predArgs.toArray());
        }

        // ③ 校验每个 checked 行的所有上游就绪(强 SUCCESS / 弱 SUCCESS|FAILED)
        for (Row r : checked) {
            List<Edge> edges = edgesByTo.get(r.workflowNodeId);
            if (edges == null) continue;  // 无 edge 已加入 ready
            boolean allReady = true;
            for (Edge e : edges) {
                Set<String> states = predStates.get(r.workflowInstanceId + ":" + e.fromNodeId());
                boolean weak = "WEAK".equals(e.strength());
                // 强依赖:pred 须 SUCCESS;弱依赖:pred 须 SUCCESS 或 FAILED(自然跑完;STOPPED 中止不算)
                boolean satisfied = states != null && (states.contains("SUCCESS")
                        || (weak && states.contains("FAILED")));
                if (!satisfied) { allReady = false; break; }
            }
            if (allReady) ready.add(r.id);
        }
        return ready;
    }

    /** 049:上游 edge(from_node_id + strength,strength 默认 STRONG)。 */
    private record Edge(Long fromNodeId, String strength) {}

    /** date_offset → bizDate 偏移：LAST_DAY=上一日(-1)，CURRENT_DAY/空=同日。 */
    private String offsetBizDate(String bizDate, String offset) {
        if ("LAST_DAY".equals(offset)) {
            try {
                return java.time.LocalDate.parse(bizDate).minusDays(1).toString();
            } catch (java.time.format.DateTimeParseException e) {
                return bizDate;  // 非标准日期保守不偏移
            }
        }
        return bizDate;
    }

    private record DepRow(Long dependNodeId, String dateOffset, String earliestBizDate) {}

    /** 取下发内容：NORMAL 用已发布版本快照；TEST/无版本用任务草稿内容。046:按版本缓存消除 N+1。 */
    private String contentOf(Row r) {
        // 最高优先：TEST 携带的编辑器临时内容（实例级 override），不读 task_def——「跑编辑器最新内容」。
        if (r.contentOverride != null && !r.contentOverride.isBlank()) {
            return r.contentOverride;
        }
        if (r.taskId == null) {
            return null;
        }
        // 草稿(无版本号)内容可变——TEST 跑草稿(D9)/未发布版本的 NORMAL 节点,编辑或 push 后须立即生效,不缓存。
        if (r.taskVersionNo == null) {
            return loadContent(r);
        }
        // 046:content 静态(版本冻结),按 taskId+version 跨轮缓存(computeIfAbsent 原子)。
        if (contentCache.size() >= VERSION_CACHE_MAX) {
            contentCache.clear();  // 简单泄压:整体清空重热(下一轮回填,避免逐条 LRU 开销)
        }
        return contentCache.computeIfAbsent(r.taskId + ":v" + r.taskVersionNo, k -> loadContent(r));
    }

    /** 046:contentOf 的 DB 加载(computeIfAbsent 首次 miss 时填 cache)。 */
    private String loadContent(Row r) {
        if (r.taskVersionNo != null) {
            List<String> c = jdbc.query(
                    "SELECT content FROM task_def_version WHERE task_id=? AND version_no=?",
                    (rs, n) -> rs.getString("content"), r.taskId, r.taskVersionNo);
            if (!c.isEmpty()) {
                return c.get(0);
            }
        }
        List<String> c = jdbc.query("SELECT content FROM task_def WHERE id=?",
                (rs, n) -> rs.getString("content"), r.taskId);
        return c.isEmpty() ? null : c.get(0);
    }

    /** 取自定义参数 JSON：与 {@link #contentOf} 同源（按版本优先 task_def_version）。046:按版本缓存。 */
    private String paramsJsonOf(Row r) {
        if (r.paramsOverride != null && !r.paramsOverride.isBlank()) {
            return r.paramsOverride;
        }
        if (r.taskId == null) {
            return null;
        }
        // 草稿可变,不缓存(同 contentOf)
        if (r.taskVersionNo == null) {
            return loadParams(r);
        }
        if (paramsCache.size() >= VERSION_CACHE_MAX) {
            paramsCache.clear();
        }
        return paramsCache.computeIfAbsent(r.taskId + ":v" + r.taskVersionNo, k -> loadParams(r));
    }

    /** 046:paramsJsonOf 的 DB 加载(computeIfAbsent 首次 miss 时填 cache)。 */
    private String loadParams(Row r) {
        if (r.taskVersionNo != null) {
            List<String> p = jdbc.query(
                    "SELECT params_json FROM task_def_version WHERE task_id=? AND version_no=?",
                    (rs, n) -> rs.getString("params_json"), r.taskId, r.taskVersionNo);
            if (!p.isEmpty()) {
                return p.get(0);
            }
        }
        List<String> p = jdbc.query("SELECT params_json FROM task_def WHERE id=?",
                (rs, n) -> rs.getString("params_json"), r.taskId);
        return p.isEmpty() ? null : p.get(0);
    }

    /**
     * 从扁平 JSON 提取字符串值（params_json 由平台生成，{@code _sparkMode} 等键值为字符串，
     * 无需引 ObjectMapper；与 {@code LocalRunMain} 的 ds-json 解析同法）。
     */
    static String jsonStr(String json, String key) {
        if (json == null || json.isBlank()) {
            return null;
        }
        var m = java.util.regex.Pattern
                .compile("\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(json);
        return m.find() ? m.group(1).replace("\\\"", "\"").replace("\\\\", "\\") : null;
    }

    /**
     * 解析 content 里的调度参数占位符；失败则 CAS 置实例 FAILED 并返回 {@code null}（不下发）。
     * 在认领事务内调用，故解析异常被吞掉而非抛穿事务——避免一个坏 content 连坐同批次。
     */
    private String resolveContentSafely(Row r, LocalDateTime now) {
        String raw = contentOf(r);
        if (!ScheduleParamResolver.hasPlatformPlaceholder(raw)) {
            return raw;  // 无平台占位符原样返回（放过 bash $(...) 等；hasPlatformPlaceholder 已处理 null）
        }
        try {
            return paramResolver.resolve(raw, r.bizDate, paramsJsonOf(r), builtInContext(r, now));
        } catch (ScheduleParamResolver.UnresolvedPlaceholderException e) {
            // failure_reason 落库默认中文（后台无请求 locale）：经 Messages 把 code+args 渲染为含占位符名的可读文案，
            // 不直接落 e.getMessage()（=i18n code），否则实例详情只剩裸 key、丢失诊断信息。
            String reason = messages.get(e.getCode(), e.getArgs());
            log.warn("[Scheduler] 实例 {} 占位符解析失败，置 FAILED：{}", r.id, reason);
            stateMachine.casTaskTerminal(r.id, InstanceStates.DISPATCHED, InstanceStates.FAILED, reason);
            return null;
        }
    }

    private ScheduleParamResolver.BuiltInContext builtInContext(Row r, LocalDateTime now) {
        return new ScheduleParamResolver.BuiltInContext(
                r.workflowInstanceId == null ? null : r.workflowInstanceId.toString(),
                r.workflowNodeId == null ? null : r.workflowNodeId.toString(),
                r.id == null ? null : r.id.toString(),
                now.toLocalDate());
    }

    // ─── 内部数据结构 ─────────────────────────────────────

    static final class Row {  // 048:package-private 供批量化测试(batchCrossCycleReady)
        UUID id;
        UUID workflowInstanceId;
        Long workflowNodeId;
        Long taskId;
        Integer taskVersionNo;
        String contentOverride;
        String paramsOverride;
        Integer attempt;
        String runMode;
        String bizDate;
        LocalDateTime waitingSince;
        Integer priority;
        Integer timeoutSec;
        String taskType;
        Long datasourceId;
        String locale;
        String workflowTrigger;
        Long workflowId;  // 048:批量跨周期判定用(workflow_instance→workflow_id)

        boolean test() {
            return "TEST".equals(runMode);
        }
    }

    private static final class NodeSlot {
        final String code;
        final int capacity;
        final int reserved;
        final Double loadAvg;
        int used;

        NodeSlot(String code, int used, int capacity, int reserved, Double loadAvg) {
            this.code = code;
            this.used = used;
            this.capacity = capacity;
            this.reserved = reserved;
            this.loadAvg = loadAvg;
        }

        int free() {
            return capacity - used;
        }

        int normalFree() {
            return (capacity - reserved) - used;
        }
    }
}
