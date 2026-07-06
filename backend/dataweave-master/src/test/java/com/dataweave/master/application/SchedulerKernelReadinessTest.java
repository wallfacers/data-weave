package com.dataweave.master.application;

import com.dataweave.master.application.ParallelDispatcher;
import com.dataweave.master.application.ScheduleParamResolver;
import com.dataweave.master.application.SchedulerKernel;
import com.dataweave.master.application.SchedulerMetrics;
import com.dataweave.master.application.SchedulingPolicy;
import com.dataweave.master.application.SlotManager;
import com.dataweave.master.application.TaskExecutionGateway;
import com.dataweave.master.application.PreemptionService;
import com.dataweave.master.application.readiness.ReadinessRecompute;
import com.dataweave.master.domain.EventBus;
import com.dataweave.master.i18n.Messages;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * T023: SchedulerKernelReadinessTest — 认领只取 unmet_deps=0，端到端 A→B 强依赖。
 */
@DisplayName("SchedulerKernelReadiness")
class SchedulerKernelReadinessTest {

    private JdbcTemplate jdbc;
    private SchedulerKernel kernel;

    @BeforeEach
    void setUp() {
        var ds = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("test-kernel-readiness-" + System.currentTimeMillis())
                .build();
        jdbc = new JdbcTemplate(ds);
        com.dataweave.master.application.readiness.ReadinessTestHelper.createMinimalSchema(jdbc);
        kernel = new SchedulerKernel(jdbc,
                mock(InstanceStateMachine.class), mock(SlotManager.class),
                mock(SchedulingPolicy.class), mock(TaskExecutionGateway.class),
                mock(EventBus.class), mock(PreemptionService.class),
                new SchedulerMetrics(new SimpleMeterRegistry(), jdbc),
                mock(ParallelDispatcher.class),
                mock(ScheduleParamResolver.class), mock(Messages.class),
                mock(PlatformTransactionManager.class),
                50, 120, 200, 5,
                true); // materialized=true — 启用 unmet_deps=0 过滤
    }

    @Test
    @DisplayName("unmet_deps=0 实例在认领候选内")
    void readyInstanceInClaimCandidates() {
        seedEnv();
        UUID readyId = seedWaitingInstance(0);   // unmet=0，应就绪

        var rows = selectRunnable(SchedulerKernel.RunMode.NORMAL);
        assertThat(rows).isNotEmpty();
        assertThat(rows.stream().anyMatch(r -> r.id.equals(readyId)))
                .as("unmet_deps=0 实例应出现在认领候选内").isTrue();
    }

    @Test
    @DisplayName("unmet_deps>0 实例不在认领候选内")
    void notReadyInstanceExcluded() {
        seedEnv();
        seedWaitingInstance(2); // unmet=2，未就绪

        var rows = selectRunnable(SchedulerKernel.RunMode.NORMAL);
        assertThat(rows).hasSize(0).as("unmet>0 不应被认领");
    }

    @Test
    @DisplayName("端到端 A→B 强依赖：B unmet=1→A SUCCESS→重算 B=0→B 可认领")
    void e2eStrongDependency() {
        // 搭 DAG: A→B STRONG
        seedEnv();
        jdbc.update("INSERT INTO workflow_def (tenant_id, project_id, name) VALUES (1,1,'wf')");
        UUID wiId = UUID.randomUUID();
        jdbc.update("INSERT INTO workflow_instance (id, tenant_id, project_id, workflow_id, state, " +
                "trigger_type) VALUES (?,1,1,1,'RUNNING','CRON')", wiId);
        jdbc.update("INSERT INTO workflow_node (id, tenant_id, project_id, workflow_id, node_key, name) " +
                "VALUES (1,1,1,1,'A','A'), (2,1,1,1,'B','B')");
        jdbc.update("INSERT INTO workflow_edge (tenant_id, project_id, workflow_id, from_node_id, to_node_id, " +
                "strength) VALUES (1,1,1,1,2,'STRONG')");

        // A RUNNING, B WAITING with unmet=1（模拟物化时计算的初值）
        UUID aId = UUID.randomUUID();
        UUID bId = UUID.randomUUID();
        jdbc.update("INSERT INTO task_instance (id, tenant_id, project_id, workflow_instance_id, " +
                "workflow_node_id, biz_date, state, deleted, unmet_deps, run_mode) " +
                "VALUES (?,1,1,?,?,?,?,?,?,?)",
                aId, wiId, 1L, "2026-07-06", "RUNNING", 0, 0, "NORMAL");
        jdbc.update("INSERT INTO task_instance (id, tenant_id, project_id, workflow_instance_id, " +
                "workflow_node_id, biz_date, state, deleted, unmet_deps, run_mode) " +
                "VALUES (?,1,1,?,?,?,?,?,?,?)",
                bId, wiId, 2L, "2026-07-06", "WAITING", 0, 1, "NORMAL");

        // 阶段 1: B unmet=1 → 不可认领
        var rows = selectRunnable(SchedulerKernel.RunMode.NORMAL);
        assertThat(rows.stream().noneMatch(r -> r.id.equals(bId)))
                .as("B unmet=1 时不应被认领").isTrue();

        // 阶段 2: A → SUCCESS，重算 B unmet=0
        jdbc.update("UPDATE task_instance SET state='SUCCESS' WHERE id=?", aId);
        // 模拟 Maintainer 重算
        ReadinessRecompute recompute = new ReadinessRecompute(jdbc);
        int newUnmet = recompute.recomputeSingle(bId);
        assertThat(newUnmet).isEqualTo(0).as("A SUCCESS 后 B 重算为就绪");
        jdbc.update("UPDATE task_instance SET unmet_deps=? WHERE id=?", newUnmet, bId);

        // 阶段 3: B 可认领
        rows = selectRunnable(SchedulerKernel.RunMode.NORMAL);
        assertThat(rows.stream().anyMatch(r -> r.id.equals(bId)))
                .as("B unmet=0 后应可被认领").isTrue();
    }

    @Test
    @DisplayName("TEST run_mode 不受 unmet_deps 过滤影响")
    void testModeUnaffected() {
        seedEnv();
        UUID testId = UUID.randomUUID();
        jdbc.update("INSERT INTO test_wi (id, wi) VALUES ('testwi', ?)", UUID.randomUUID());
        jdbc.update("INSERT INTO task_instance (id, tenant_id, project_id, workflow_instance_id, " +
                "state, deleted, unmet_deps, run_mode) " +
                "VALUES (?,1,1,?,'WAITING',0,5,'TEST')", testId, UUID.randomUUID());

        var rows = selectRunnable(SchedulerKernel.RunMode.TEST);
        assertThat(rows.stream().anyMatch(r -> r.id.equals(testId)))
                .as("TEST 模式不检查 unmet_deps").isTrue();
    }

    // ─── T025 idempotency 回归（046/048/049 恰好一次未被 unmet 过滤破坏）───
    // 认领的"恰好一次"由 CAS（UPDATE … WHERE state='WAITING'）保证，与 unmet_deps 过滤
    // （selectRunnable 层）正交：物化只追加过滤条件，不改 CAS 语义。故直接实例化真实
    // InstanceStateMachine 验 CAS（kernel 测试里 stateMachine 是 mock）。
    // 注：此处用单条 casDispatch（H2/PG 双兼容）验 CAS 语义；casDispatchBatch 的 PG 风格
    // UPDATE FROM VALUES 在 H2 不被支持（048 既有约束，InstanceStateMachine.java:76 注释自承
    // T1 FAIL/T5 OK），其批量 idempotency 由 048 PG 测试 + T026 docker 真跑覆盖。单条与批量
    // 共用 WHERE state=? CAS，语义等价。

    @Test
    @DisplayName("T025: 同实例 casDispatch 重复调用恰好一次（CAS WHERE state=WAITING）")
    void casDispatchExactlyOnce() {
        seedEnv();
        InstanceStateMachine sm = new InstanceStateMachine(jdbc,
                mock(EventBus.class), mock(ApplicationEventPublisher.class));
        UUID id = seedWaitingInstance(0);   // unmet=0，WAITING
        LocalDateTime now = LocalDateTime.now();

        // 首次 CAS：WAITING → DISPATCHED 成功
        boolean first = sm.casDispatch(id, "WAITING", "worker-1", now.plusSeconds(120), 1);
        assertThat(first).as("首次 casDispatch WAITING→DISPATCHED 成功").isTrue();

        // 二次 CAS 同实例：state 已 DISPATCHED → WHERE state='WAITING' 不命中 → false（让步，不重复下发）
        boolean second = sm.casDispatch(id, "WAITING", "worker-1", now.plusSeconds(120), 1);
        assertThat(second).as("二次 casDispatch 应失败（CAS 恰好一次，无重复下发）").isFalse();

        String state = jdbc.queryForObject("SELECT state FROM task_instance WHERE id=?", String.class, id);
        assertThat(state).as("实例仅被推进一次到 DISPATCHED").isEqualTo("DISPATCHED");
    }

    @Test
    @DisplayName("T025: 多实例 casDispatch 各恰好一次；已 DISPATCHED 的不再被认领（防重复下发）")
    void casDispatchNoDoubleDispatch() {
        seedEnv();
        InstanceStateMachine sm = new InstanceStateMachine(jdbc,
                mock(EventBus.class), mock(ApplicationEventPublisher.class));
        UUID w1 = seedWaitingInstance(0);
        UUID w2 = seedWaitingInstance(0);
        LocalDateTime now = LocalDateTime.now();

        assertThat(sm.casDispatch(w1, "WAITING", "worker-1", now.plusSeconds(120), 1)).isTrue();
        assertThat(sm.casDispatch(w2, "WAITING", "worker-1", now.plusSeconds(120), 1)).isTrue();

        // 模拟另一 master 并发可见 w1（已 DISPATCHED）→ CAS 失败，不重复下发，worker 不被覆盖
        assertThat(sm.casDispatch(w1, "WAITING", "worker-2", now.plusSeconds(120), 1))
                .as("已 DISPATCHED 实例不被二次认领（CAS 防重复下发）").isFalse();
        String worker = jdbc.queryForObject("SELECT worker_node_code FROM task_instance WHERE id=?", String.class, w1);
        assertThat(worker).as("首次认领者不被二次覆盖").isEqualTo("worker-1");
    }

    // ─── 辅助 ────────────────────────────────────────────────

    private void seedEnv() {
        jdbc.update("INSERT INTO tenants (code, name) VALUES ('t','t')");
        jdbc.update("INSERT INTO projects (tenant_id, code, name) VALUES (1,'p','p')");
        jdbc.update("INSERT INTO task_def (tenant_id, project_id, name, type) VALUES (1,1,'td','SHELL')");
        jdbc.update("CREATE TABLE IF NOT EXISTS test_wi (id VARCHAR PRIMARY KEY, wi VARCHAR)");
    }

    private UUID seedWaitingInstance(int unmetDeps) {
        UUID id = UUID.randomUUID();
        UUID wiId = UUID.randomUUID();
        long nodeId = System.currentTimeMillis(); // unique node id
        jdbc.update("INSERT INTO workflow_def (tenant_id, project_id, name) VALUES (1,1,CONCAT('wf',?))", nodeId);
        jdbc.update("INSERT INTO workflow_node (id, tenant_id, project_id, workflow_id, node_key, name) " +
                "VALUES (?,1,1,1,CONCAT('n',?),CONCAT('node',?))", nodeId, nodeId, nodeId);
        jdbc.update("INSERT INTO workflow_instance (id, tenant_id, project_id, workflow_id, state) " +
                "VALUES (?,1,1,1,'RUNNING')", wiId);
        jdbc.update("INSERT INTO task_instance (id, tenant_id, project_id, workflow_instance_id, " +
                "workflow_node_id, state, deleted, unmet_deps, run_mode) " +
                "VALUES (?,1,1,?,?,'WAITING',0,?,'NORMAL')", id, wiId, nodeId, unmetDeps);
        return id;
    }

    @SuppressWarnings("unchecked")
    private java.util.List<SchedulerKernel.Row> selectRunnable(SchedulerKernel.RunMode mode) {
        try {
            var method = SchedulerKernel.class.getDeclaredMethod("selectRunnable",
                    SchedulerKernel.RunMode.class, SchedulerKernel.Row.class);
            method.setAccessible(true);
            return (java.util.List<SchedulerKernel.Row>) method.invoke(kernel, mode, null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
