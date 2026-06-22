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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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

    // 串行化一轮调度（CAS 已保正确，串行仅去重避免空抢）；运行中再来唤醒则置 rerun，结束后补跑。
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean rerun = new AtomicBoolean(false);

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
                           @Value("${scheduler.lease-seconds:120}") long leaseSeconds) {
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
        try {
            dispatched = txTemplate.execute(status -> claimAndMark());
        } catch (Exception e) {
            log.warn("[Scheduler] 认领事务失败：{}", e.getMessage());
            metrics.endRound(roundSample);
            return;
        }
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
        // 事务外并行下发（副作用，不持 DB 锁）；失败回退 WAITING 重派。屏障语义：全部完成才返回，保持轮次串行。
        dispatcher.dispatchAll(dispatched, gateway::dispatch, (cmd, err) -> {
            log.warn("[Scheduler] 下发实例 {} 失败，回退 WAITING：{}", cmd.taskInstanceId(), err.getMessage());
            stateMachine.casRequeue(cmd.taskInstanceId(), InstanceStates.DISPATCHED);
        });
        metrics.endRound(roundSample);
    }

    /** 在事务内认领可运行实例、分配节点、CAS 置 DISPATCHED，返回待下发指令。 */
    private List<DispatchCommand> claimAndMark() {
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

        // TEST 先行（高优 + 可用全部空槽，含预留）
        List<Row> tests = selectRunnable(true);
        assign(tests, nodes, true, now, out);

        // NORMAL 次之（仅非预留空槽），按策略有效优先级排序
        List<Row> normals = selectRunnable(false);
        normals.sort(Comparator.comparingInt(r -> policy.effectivePriority(toCandidate(r), now)));
        assign(normals, nodes, false, now, out);

        return out;
    }

    private void assign(List<Row> rows, List<NodeSlot> nodes, boolean test, LocalDateTime now,
                        List<DispatchCommand> out) {
        for (Row r : rows) {
            List<NodeLoad> avail = new ArrayList<>();
            for (NodeSlot ns : nodes) {
                int free = test ? ns.free() : ns.normalFree();
                if (free > 0) {
                    // NodeLoad.free()==capacity-used，须等于本模式可用槽 free；故 used 取 capacity-free。
                    // （原写法误传 capacity-used 作 used，使空闲节点 free()=0 永不入选，busy 节点反被选——
                    //  all-in-one 因种子节点有负载 + 进程内网关无视节点码而被掩盖，distributed 下暴露。）
                    avail.add(new NodeLoad(stub(ns), ns.capacity - free, ns.capacity));
                }
            }
            policy.place(toCandidate(r), avail).ifPresent(chosen -> {
                String code = chosen.node().getNodeCode();
                NodeSlot ns = find(nodes, code);
                int attempt = (r.attempt == null ? 0 : r.attempt) + 1;
                LocalDateTime lease = now.plusSeconds(leaseSeconds);
                if (stateMachine.casDispatch(r.id, InstanceStates.WAITING, code, lease, attempt)) {
                    ns.used++;
                    String content = resolveContentSafely(r, now);
                    if (content == null) {
                        return;  // 占位符解析失败：已 CAS 置 FAILED，不下发
                    }
                    int timeout = r.timeoutSec != null ? r.timeoutSec : 0;
                    out.add(new DispatchCommand(r.id, attempt, code, r.taskId, r.taskVersionNo,
                            r.runMode, r.bizDate, content, timeout, r.taskType, r.datasourceId, r.locale));
                }
            });
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

    /** 认领可运行候选（FOR UPDATE SKIP LOCKED 锁取，事务内）。test=true 取 TEST，false 取 NORMAL（带上游就绪门）。 */
    private List<Row> selectRunnable(boolean test) {
        String sql;
        if (test) {
            sql = "SELECT ti.id, ti.workflow_instance_id, ti.workflow_node_id, ti.task_id, ti.task_version_no, "
                    + "ti.content_override, ti.params_override, "
                    + "ti.attempt, ti.run_mode, ti.biz_date, ti.updated_at, "
                    + "(SELECT wi.priority FROM workflow_instance wi WHERE wi.id=ti.workflow_instance_id) AS wpriority, "
                    + "(SELECT td.timeout_sec FROM task_def td WHERE td.id=ti.task_id) AS timeout_sec, "
                    + "COALESCE(ti.type_override, (SELECT td.type FROM task_def td WHERE td.id=ti.task_id)) AS task_type, "
                    + "(SELECT td.datasource_id FROM task_def td WHERE td.id=ti.task_id) AS datasource_id, "
                    + "ti.locale "
                    + "FROM task_instance ti "
                    + "WHERE ti.state='WAITING' AND ti.run_mode='TEST' AND ti.deleted=0 "
                    + "ORDER BY ti.updated_at ASC "
                    + "LIMIT " + claimBatchSize + " FOR UPDATE SKIP LOCKED";
        } else {
            sql = "SELECT ti.id, ti.workflow_instance_id, ti.workflow_node_id, ti.task_id, ti.task_version_no, "
                    + "ti.content_override, ti.params_override, "
                    + "ti.attempt, ti.run_mode, ti.biz_date, ti.updated_at, "
                    + "(SELECT wi.priority FROM workflow_instance wi WHERE wi.id=ti.workflow_instance_id) AS wpriority, "
                    + "(SELECT td.timeout_sec FROM task_def td WHERE td.id=ti.task_id) AS timeout_sec, "
                    + "COALESCE(ti.type_override, (SELECT td.type FROM task_def td WHERE td.id=ti.task_id)) AS task_type, "
                    + "(SELECT td.datasource_id FROM task_def td WHERE td.id=ti.task_id) AS datasource_id, "
                    + "ti.locale "
                    + "FROM task_instance ti "
                    + "WHERE ti.state='WAITING' AND ti.run_mode='NORMAL' AND ti.deleted=0 "
                    + "AND (ti.workflow_instance_id IS NULL OR (SELECT wi.state FROM workflow_instance wi "
                    + "     WHERE wi.id=ti.workflow_instance_id) NOT IN ('PAUSED','STOPPED')) "
                    + "AND NOT EXISTS (SELECT 1 FROM workflow_edge e "
                    + "   JOIN task_instance pred ON pred.workflow_instance_id=ti.workflow_instance_id "
                    + "        AND pred.workflow_node_id=e.from_node_id AND pred.deleted=0 "
                    + "   WHERE e.to_node_id=ti.workflow_node_id AND e.deleted=0 AND pred.state<>'SUCCESS') "
                    + "ORDER BY ti.updated_at ASC "
                    + "LIMIT " + claimBatchSize + " FOR UPDATE SKIP LOCKED";
        }
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
            return r;
        });
    }

    /** 取下发内容：NORMAL 用已发布版本快照；TEST/无版本用任务草稿内容。 */
    private String contentOf(Row r) {
        // 最高优先：TEST 携带的编辑器临时内容（实例级 override），不读 task_def——「跑编辑器最新内容」。
        if (r.contentOverride != null && !r.contentOverride.isBlank()) {
            return r.contentOverride;
        }
        if (r.taskId == null) {
            return null;
        }
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

    /** 取自定义参数 JSON：与 {@link #contentOf} 同源（按版本优先 task_def_version）。 */
    private String paramsJsonOf(Row r) {
        // 与 contentOf 同源：TEST 携带的临时参数优先，保证 ${自定义} 占位符按编辑器解析。
        if (r.paramsOverride != null && !r.paramsOverride.isBlank()) {
            return r.paramsOverride;
        }
        if (r.taskId == null) {
            return null;
        }
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
     * 解析 content 里的调度参数占位符；失败则 CAS 置实例 FAILED 并返回 {@code null}（不下发）。
     * 在认领事务内调用，故解析异常被吞掉而非抛穿事务——避免一个坏 content 连坐同批次。
     */
    private String resolveContentSafely(Row r, LocalDateTime now) {
        String raw = contentOf(r);
        if (raw == null || raw.indexOf('$') < 0) {
            return raw;  // 无占位符原样返回
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

    private static final class Row {
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
