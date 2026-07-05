package com.dataweave.api;

import com.dataweave.master.domain.InstanceStates;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.TaskInstanceRepository;
import com.dataweave.master.domain.WorkflowDef;
import com.dataweave.master.domain.WorkflowDefRepository;
import com.dataweave.master.domain.WorkflowInstanceRepository;
import com.dataweave.master.application.SchedulerKernel;
import com.dataweave.master.application.WorkflowTriggerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 调度内核端到端（all-in-one，H2）：触发工作流 → 认领 → 下发 → 进程内执行 → 回报 → DAG 下游解锁
 * → 工作流聚合 SUCCESS（task 2.2/2.3/2.4/2.5 + 执行闭环）。
 */
@SpringBootTest
@ActiveProfiles("h2")
// 独立 H2 内存库：h2 profile 默认 url 固定库名 + DB_CLOSE_DELAY=-1，全 JVM 所有 test context 共享同一库，
// 致 worker_nodes 状态被先跑的测试（SchedulingParameterIntegrationTest 置全 OFFLINE、LeaseReaper/Preemption/
// FleetService 等）污染，本端到端测试依赖种子 ONLINE worker 下发执行故满载时认领不到 worker 而超时。
// 专属库名 → 独立 context 重跑 schema.sql + data.sql → 干净 ONLINE worker，复刻隔离跑条件。
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:kernelsched;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
})
// ec7868e 移除 worker_nodes 产品 seed 后,MOCK 环境无 HTTP 心跳注册,调度 E2E 须自备 ONLINE worker。
@Sql(scripts = "/test-worker-seed.sql")
class KernelSchedulingTest {

    @Autowired
    WorkflowTriggerService triggerService;
    @Autowired
    WorkflowDefRepository workflowDefRepository;
    @Autowired
    WorkflowInstanceRepository workflowInstanceRepository;
    @Autowired
    TaskInstanceRepository taskInstanceRepository;
    @Autowired
    SchedulerKernel schedulerKernel;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void triggeredWorkflow_runsAllNodes_andAggregatesSuccess() throws Exception {
        // 种子 workflow id=3「订单 SHELL 流水线」（6 个 SHELL 节点，分叉+合并 DAG）。
        WorkflowDef wf = workflowDefRepository.findById(3L).orElseThrow();

        UUID wiId = triggerService.trigger(wf, "MANUAL", "2026-06-11", null, java.util.Locale.SIMPLIFIED_CHINESE);

        boolean done = await(Duration.ofSeconds(20), () ->
                InstanceStates.SUCCESS.equals(
                        workflowInstanceRepository.findById(wiId).map(w -> w.getState()).orElse(null)));

        assertThat(done).as("工作流应在超时内聚合为 SUCCESS").isTrue();

        List<TaskInstance> nodes = taskInstanceRepository.findByWorkflowInstanceId(wiId);
        assertThat(nodes).isNotEmpty();
        assertThat(nodes).allMatch(n -> InstanceStates.SUCCESS.equals(n.getState()));
        // 每个节点都被真实下发执行（有 worker 落点 + attempt 递增）
        assertThat(nodes).allMatch(n -> n.getWorkerNodeCode() != null);
        assertThat(nodes).allMatch(n -> n.getAttempt() != null && n.getAttempt() >= 1);
    }

    @Test
    void weakDependency_upstreamTerminal_downstreamReleased() throws Exception {
        // 弱依赖：n1(node4)→n2(node5) 边(edge id=3)设为 WEAK，上游到终态 FAILED 后下游应被放行。
        WorkflowDef wf = workflowDefRepository.findById(3L).orElseThrow();
        UUID wiId = triggerService.trigger(wf, "MANUAL", "2026-06-11", null, java.util.Locale.SIMPLIFIED_CHINESE);
        assertThat(await(Duration.ofSeconds(20), () -> InstanceStates.SUCCESS.equals(
                workflowInstanceRepository.findById(wiId).map(w -> w.getState()).orElse(null))))
                .as("前置：工作流先跑完 SUCCESS").isTrue();

        UUID n1Inst = instanceIdForNode(wiId, 4L);
        UUID n2Inst = instanceIdForNode(wiId, 5L);
        jdbc.update("UPDATE workflow_edge SET strength='WEAK' WHERE id=3");
        jdbc.update("UPDATE task_instance SET state='FAILED' WHERE id=?", n1Inst);
        jdbc.update("UPDATE task_instance SET state='WAITING', worker_node_code=NULL, lease_expire_at=NULL WHERE id=?", n2Inst);

        schedulerKernel.scheduleOnce();
        boolean released = await(Duration.ofSeconds(10), () ->
                !InstanceStates.WAITING.equals(taskInstanceState(n2Inst)));
        assertThat(released).as("弱依赖：上游到达终态 FAILED 后下游应被放行（离开 WAITING）").isTrue();
    }

    @Test
    void strongDependency_upstreamFailed_downstreamBlocked() throws Exception {
        // 强依赖对照：n2(node5)→n4(node7) 边(edge id=5)保持 STRONG，上游 FAILED 时下游应保持 WAITING。
        WorkflowDef wf = workflowDefRepository.findById(3L).orElseThrow();
        UUID wiId = triggerService.trigger(wf, "MANUAL", "2026-06-11", null, java.util.Locale.SIMPLIFIED_CHINESE);
        assertThat(await(Duration.ofSeconds(20), () -> InstanceStates.SUCCESS.equals(
                workflowInstanceRepository.findById(wiId).map(w -> w.getState()).orElse(null))))
                .as("前置：工作流先跑完 SUCCESS").isTrue();

        UUID n2Inst = instanceIdForNode(wiId, 5L);
        UUID n4Inst = instanceIdForNode(wiId, 7L);
        jdbc.update("UPDATE task_instance SET state='FAILED' WHERE id=?", n2Inst);
        jdbc.update("UPDATE task_instance SET state='WAITING', worker_node_code=NULL, lease_expire_at=NULL WHERE id=?", n4Inst);

        schedulerKernel.scheduleOnce();
        Thread.sleep(1000); // 让本轮认领事务落实
        assertThat(taskInstanceState(n4Inst))
                .as("强依赖：上游 FAILED 时下游应保持 WAITING").isEqualTo(InstanceStates.WAITING);
    }

    @Test
    void runToNode_materializesTargetAndPredecessors() {
        // wf3 DAG: n1(4)→n2(5)→{n3(6),n4(7)}→n5(8)→n6(9)。TO_NODE n5：物化 n5 及前驱闭包 n1..n4，不含下游 n6。
        WorkflowDef wf = workflowDefRepository.findById(3L).orElseThrow();
        UUID wiId = triggerService.trigger(wf, "MANUAL", "2026-06-11", null,
                java.util.Locale.SIMPLIFIED_CHINESE, "TO_NODE", "n5");
        Set<Long> nodeIds = taskInstanceRepository.findByWorkflowInstanceId(wiId).stream()
                .map(TaskInstance::getWorkflowNodeId).collect(Collectors.toSet());
        assertThat(nodeIds).containsExactlyInAnyOrder(4L, 5L, 6L, 7L, 8L);
    }

    @Test
    void runDownstream_materializesTargetAndSuccessors() {
        // DOWNSTREAM n2：物化 n2 及后继闭包 n3..n6，不含上游 n1。
        WorkflowDef wf = workflowDefRepository.findById(3L).orElseThrow();
        UUID wiId = triggerService.trigger(wf, "MANUAL", "2026-06-11", null,
                java.util.Locale.SIMPLIFIED_CHINESE, "DOWNSTREAM", "n2");
        Set<Long> nodeIds = taskInstanceRepository.findByWorkflowInstanceId(wiId).stream()
                .map(TaskInstance::getWorkflowNodeId).collect(Collectors.toSet());
        assertThat(nodeIds).containsExactlyInAnyOrder(5L, 6L, 7L, 8L, 9L);
    }

    @Test
    void runFull_materializesAllNodes() {
        WorkflowDef wf = workflowDefRepository.findById(3L).orElseThrow();
        UUID wiId = triggerService.trigger(wf, "MANUAL", "2026-06-11", null,
                java.util.Locale.SIMPLIFIED_CHINESE, "FULL", null);
        Set<Long> nodeIds = taskInstanceRepository.findByWorkflowInstanceId(wiId).stream()
                .map(TaskInstance::getWorkflowNodeId).collect(Collectors.toSet());
        assertThat(nodeIds).containsExactlyInAnyOrder(4L, 5L, 6L, 7L, 8L, 9L);
    }

    @Test
    void backfillHeldInstance_notClaimed_untilReleased() throws Exception {
        // 节流认领守卫（backfill-parallelism-throttle，6.7）：held=1 的补数据实例不被认领；置 0 后被放行。
        Long taskId = jdbc.queryForObject(
                "SELECT task_id FROM workflow_node WHERE workflow_id=3 AND task_id IS NOT NULL ORDER BY id LIMIT 1",
                Long.class);
        // 随机 runId：不建 backfill_run 行 → BackfillPromoter 扫不到 RUNNING 批次，不会自动晋升，测试稳定。
        UUID runId = UUID.fromString("00000000-0000-7000-8000-0000000000ff");
        UUID heldInst = triggerService.triggerBackfillTaskRun(
                taskId, "2026-06-11", runId, 1, java.util.Locale.SIMPLIFIED_CHINESE);

        schedulerKernel.scheduleOnce();
        Thread.sleep(800); // 给异步认领留窗口；held=1 应始终被守卫排除
        assertThat(taskInstanceState(heldInst))
                .as("held=1 的补数据实例不应被认领").isEqualTo(InstanceStates.WAITING);

        // 晋升：held→0 → 应被认领，离开 WAITING。
        jdbc.update("UPDATE task_instance SET backfill_held=0 WHERE id=?", heldInst);
        schedulerKernel.scheduleOnce();
        boolean released = await(Duration.ofSeconds(15), () ->
                !InstanceStates.WAITING.equals(taskInstanceState(heldInst)));
        assertThat(released).as("置 held=0 后应被认领（离开 WAITING）").isTrue();
    }

    private UUID instanceIdForNode(UUID wiId, Long nodeId) {
        return jdbc.queryForObject(
                "SELECT id FROM task_instance WHERE workflow_instance_id=? AND workflow_node_id=? AND deleted=0",
                UUID.class, wiId, nodeId);
    }

    private String taskInstanceState(UUID id) {
        return jdbc.queryForObject("SELECT state FROM task_instance WHERE id=?", String.class, id);
    }

    private boolean await(Duration timeout, java.util.function.BooleanSupplier cond) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (cond.getAsBoolean()) {
                return true;
            }
            Thread.sleep(100);
        }
        return cond.getAsBoolean();
    }
}
