package com.dataweave.master.application.readiness;

import com.dataweave.master.application.SchedulerMetrics;
import com.dataweave.master.domain.EventBus;
import com.dataweave.master.infrastructure.JdbcReadinessSignalRepository;
import com.dataweave.master.infrastructure.ReadinessSignalRow;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * T021: ReadinessMaintainer 单测 — 信号→受影响下游正确重算→wake。
 */
@DisplayName("ReadinessMaintainer")
class ReadinessMaintainerTest {

    private JdbcTemplate jdbc;
    private JdbcReadinessSignalRepository signalRepo;
    private ReadinessRecompute recompute;
    private SchedulerMetrics metrics;
    private EventBus eventBus;

    @BeforeEach
    void setUp() {
        var ds = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("test-maint-" + System.currentTimeMillis())
                .build();
        jdbc = new JdbcTemplate(ds);
        ReadinessTestHelper.createMinimalSchema(jdbc);
        ReadinessTestHelper.createReadinessSignalTable(jdbc);
        signalRepo = new JdbcReadinessSignalRepository(jdbc);
        recompute = new ReadinessRecompute(jdbc);
        metrics = new SchedulerMetrics(new SimpleMeterRegistry(), jdbc);
        eventBus = mock(EventBus.class);
    }

    @Test
    @DisplayName("信号→下游重算→unmet 正确更新")
    void signalToRecompute() {
        seedDagAtoB("STRONG");
        UUID aId = seedInstance("A", "SUCCESS");
        UUID bId = seedInstance("B", "WAITING");

        // 写入信号（模拟 WorkerReportService）
        signalRepo.insert(ReadinessSignalRow.terminal(1L, 1L, aId, 1L,
                UUID.fromString(jdbc.queryForObject("SELECT wi FROM test_wi WHERE id='wikey'", String.class)),
                1L, "2026-07-06"));

        // Maintain 处理
        var signals = signalRepo.pollPending(10);
        assertThat(signals).hasSize(1);

        Map<UUID, Integer> newUnmets = recompute.recomputeFromTerminal(aId);
        for (var e : newUnmets.entrySet()) {
            jdbc.update("UPDATE task_instance SET unmet_deps=?, updated_at=CURRENT_TIMESTAMP WHERE id=?",
                    e.getValue(), e.getKey());
        }
        signalRepo.markProcessed(List.of(signals.get(0).id()));

        // 验证 B 的 unmet 被更新
        Integer unmet = jdbc.queryForObject(
                "SELECT unmet_deps FROM task_instance WHERE id=?", Integer.class, bId);
        assertThat(unmet).isNotNull().isEqualTo(0).as("A 已 SUCCESS，B unmet 应更新为 0");
    }

    @Test
    @DisplayName("信号处理后 processed=1")
    void signalMarkedProcessed() {
        seedDagAtoB("STRONG");
        UUID aId = seedInstance("A", "SUCCESS");
        seedInstance("B", "WAITING");

        long signalId = signalRepo.insert(ReadinessSignalRow.terminal(1L, 1L, aId, null, null, null, null));

        var signals = signalRepo.pollPending(10);
        assertThat(signals).hasSize(1);
        signalRepo.markProcessed(List.of(signalId));

        signals = signalRepo.pollPending(10);
        assertThat(signals).hasSize(0).as("处理后不应再被领取");
    }

    @Test
    @DisplayName("SKIP LOCKED 领取限制 batch 数")
    void skipLockedBatchLimit() {
        for (int i = 0; i < 5; i++) {
            signalRepo.insert(ReadinessSignalRow.terminal(1L, 1L, UUID.randomUUID(), null, null, null, null));
        }
        var signals = signalRepo.pollPending(3);
        assertThat(signals).hasSize(3).as("应遵守 batch limit");
    }

    @Test
    @DisplayName("无信号时 pollPending 返回空")
    void noSignalsEmpty() {
        var signals = signalRepo.pollPending(10);
        assertThat(signals).hasSize(0);
    }

    @Test
    @DisplayName("重复处理同一信号 → 幂等（重算同值）")
    void repeatSignalIdempotent() {
        seedDagAtoB("STRONG");
        UUID aId = seedInstance("A", "SUCCESS");
        UUID bId = seedInstance("B", "WAITING");

        Map<UUID, Integer> first = recompute.recomputeFromTerminal(aId);
        Map<UUID, Integer> second = recompute.recomputeFromTerminal(aId);
        assertThat(first).isEqualTo(second).as("同信号重复处理应幂等");
    }

    // ─── F2 / T027：Maintainer 的 unmet UPDATE 守卫 state='WAITING' ───
    // recomputeFromTerminal 的 D 解析只取 WAITING，正常不会返回非 WAITING 实例；
    // 用 mock recompute 返回一个已 RUNNING 的实例，直接验守卫在"解析后被并发认领"的
    // torn 竞态下跳过它——不覆写其 unmet_deps、不污染 updated_at、不误 wake。

    @Test
    @DisplayName("F2: 非 WAITING 实例被 Maintainer 跳过（守卫不覆写 unmet/不误 wake）")
    void maintainerSkipsNonWaiting() {
        seedDagAtoB("STRONG");
        UUID aId = seedInstance("A", "SUCCESS");
        UUID running = seedInstance("B", "RUNNING");
        // RUNNING 实例预置 unmet=7（历史脏值），并记录旧 updated_at
        jdbc.update("UPDATE task_instance SET unmet_deps=7, updated_at=TIMESTAMP '2020-01-01 00:00:00' WHERE id=?", running);
        String oldUpdatedAt = jdbc.queryForObject(
                "SELECT updated_at FROM task_instance WHERE id=?", String.class, running);

        // 写一条信号触发 Maintainer 领取；mock recompute 强制把 RUNNING 实例塞进 D 且算得 0
        signalRepo.insert(ReadinessSignalRow.terminal(1L, 1L, aId, null, null, null, null));
        ReadinessRecompute mockRecompute = mock(ReadinessRecompute.class);
        org.mockito.Mockito.when(mockRecompute.recomputeFromTerminal(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Map.of(running, 0));

        ReadinessMaintainer maintainer = new ReadinessMaintainer(
                signalRepo, mockRecompute, metrics, eventBus, jdbc,
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                        jdbc.getDataSource()),
                1000, 100);
        maintainer.maintain();

        // 守卫命中：RUNNING 实例的 unmet 与 updated_at 均未被改
        Integer unmet = jdbc.queryForObject(
                "SELECT unmet_deps FROM task_instance WHERE id=?", Integer.class, running);
        String newUpdatedAt = jdbc.queryForObject(
                "SELECT updated_at FROM task_instance WHERE id=?", String.class, running);
        assertThat(unmet).as("非 WAITING 实例 unmet 不被 Maintainer 覆写").isEqualTo(7);
        assertThat(newUpdatedAt).as("非 WAITING 实例 updated_at 不被污染").isEqualTo(oldUpdatedAt);
        // 未真正更新任何行 → 不发 wake（prevUnmet>0 但 updated=0）
        org.mockito.Mockito.verifyNoInteractions(eventBus);
    }

    @Test
    @DisplayName("F2: WAITING 实例仍被正常维护（守卫不误伤就绪路径）")
    void maintainerStillUpdatesWaiting() {
        seedDagAtoB("STRONG");
        UUID aId = seedInstance("A", "SUCCESS");
        UUID waiting = seedInstance("B", "WAITING");
        jdbc.update("UPDATE task_instance SET unmet_deps=1 WHERE id=?", waiting);

        signalRepo.insert(ReadinessSignalRow.terminal(1L, 1L, aId, null, null, null, null));
        ReadinessRecompute mockRecompute = mock(ReadinessRecompute.class);
        org.mockito.Mockito.when(mockRecompute.recomputeFromTerminal(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Map.of(waiting, 0));

        ReadinessMaintainer maintainer = new ReadinessMaintainer(
                signalRepo, mockRecompute, metrics, eventBus, jdbc,
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                        jdbc.getDataSource()),
                1000, 100);
        maintainer.maintain();

        Integer unmet = jdbc.queryForObject(
                "SELECT unmet_deps FROM task_instance WHERE id=?", Integer.class, waiting);
        assertThat(unmet).as("WAITING 实例 unmet 正常重算落库").isEqualTo(0);
        // unmet 1→0 → 新就绪 → wake 一次
        org.mockito.Mockito.verify(eventBus).publish(
                org.mockito.ArgumentMatchers.eq(com.dataweave.master.domain.InstanceStates.WAKE_CHANNEL),
                org.mockito.ArgumentMatchers.any());
    }

    // ─── 辅助 ────────────────────────────────────────────────

    private void seedDagAtoB(String strength) {
        jdbc.update("INSERT INTO tenants (code, name) VALUES ('t','t')");
        jdbc.update("INSERT INTO projects (tenant_id, code, name) VALUES (1,'p','p')");
        jdbc.update("INSERT INTO workflow_def (tenant_id, project_id, name) VALUES (1,1,'wf')");
        UUID wiId = UUID.randomUUID();
        jdbc.update("INSERT INTO workflow_instance (id, tenant_id, project_id, workflow_id, state, " +
                "trigger_type) VALUES (?,1,1,1,'RUNNING','CRON')", wiId);
        jdbc.update("INSERT INTO workflow_node (id, tenant_id, project_id, workflow_id, node_key, name) " +
                "VALUES (1,1,1,1,'A','A'), (2,1,1,1,'B','B')");
        jdbc.update("INSERT INTO workflow_edge (tenant_id, project_id, workflow_id, from_node_id, to_node_id, " +
                "strength) VALUES (1,1,1,1,2,?)", strength);
        jdbc.update("CREATE TABLE IF NOT EXISTS test_wi (id VARCHAR PRIMARY KEY, wi VARCHAR)");
        jdbc.update("MERGE INTO test_wi KEY(id) VALUES ('wikey', ?)", wiId.toString());
    }

    private UUID seedInstance(String nodeKey, String state) {
        UUID id = UUID.randomUUID();
        Long nodeId = jdbc.queryForObject("SELECT id FROM workflow_node WHERE node_key=?", Long.class, nodeKey);
        UUID wiId = UUID.fromString(jdbc.queryForObject("SELECT wi FROM test_wi WHERE id='wikey'", String.class));
        jdbc.update("INSERT INTO task_instance (id, tenant_id, project_id, workflow_instance_id, " +
                "workflow_node_id, biz_date, state, deleted, unmet_deps) " +
                "VALUES (?,1,1,?,?,?,?,0,0)", id, wiId, nodeId, "2026-07-06", state);
        return id;
    }
}
