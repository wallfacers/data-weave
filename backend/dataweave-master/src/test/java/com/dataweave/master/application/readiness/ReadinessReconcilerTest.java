package com.dataweave.master.application.readiness;

import com.dataweave.master.application.SchedulerMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * T022: ReadinessReconciler 单测 — 漂移注入→检出自愈 + materialized 开关。
 */
@DisplayName("ReadinessReconciler")
class ReadinessReconcilerTest {

    private JdbcTemplate jdbc;
    private SchedulerMetrics metrics;


    @BeforeEach
    void setUp() {
        var ds = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("test-recon-" + System.currentTimeMillis())
                .build();
        jdbc = new JdbcTemplate(ds);
        ReadinessTestHelper.createMinimalSchema(jdbc);
        metrics = new SchedulerMetrics(new SimpleMeterRegistry(), jdbc);
    }

    @Test
    @DisplayName("注入漂移（手工改 unmet）→ 对账检出并自愈")
    void driftDetectedAndHealed() {
        // 创建数据: A→B STRONG, A=SUCCESS, B=WAITING unmet=0（正确）
        seedDagAtoB();
        UUID bId = seedInstance("B", "WAITING");

        // 手工注入漂移：B.unmet 改为 5
        jdbc.update("UPDATE task_instance SET unmet_deps=5, updated_at=CURRENT_TIMESTAMP WHERE id=?", bId);

        // Reconciler 对账
        ReadinessReconciler reconciler = new ReadinessReconciler(jdbc,
                new ReadinessRecompute(jdbc), metrics,
                mock(PlatformTransactionManager.class), 1000, 100, false);

        // 直接调内部对账
        reconciler.backfillAll();

        // 验证漂移已被修复
        Integer unmet = jdbc.queryForObject(
                "SELECT unmet_deps FROM task_instance WHERE id=?", Integer.class, bId);
        assertThat(unmet).isEqualTo(0).as("漂移应被对账修复为正确值 0");
    }

    @Test
    @DisplayName("materialized=false 开关初始状态")
    void materializedFalseInitially() {
        ReadinessReconciler reconciler = new ReadinessReconciler(jdbc,
                new ReadinessRecompute(jdbc), metrics,
                mock(PlatformTransactionManager.class), 60000, 500, false);

        assertThat(reconciler.isMaterialized()).isFalse();
    }

    @Test
    @DisplayName("backfillAll 后 materialized=true")
    void materializedTrueAfterBackfill() {
        ReadinessReconciler reconciler = new ReadinessReconciler(jdbc,
                new ReadinessRecompute(jdbc), metrics,
                mock(PlatformTransactionManager.class), 1000, 100, false);

        reconciler.backfillAll();
        assertThat(reconciler.isMaterialized()).isTrue();
        assertThat(reconciler.isBackfillDone()).isTrue();
    }

    // ─── 辅助 ────────────────────────────────────────────────

    private void seedDagAtoB() {
        jdbc.update("INSERT INTO tenants (code, name) VALUES ('t','t')");
        jdbc.update("INSERT INTO projects (tenant_id, code, name) VALUES (1,'p','p')");
        jdbc.update("INSERT INTO workflow_def (tenant_id, project_id, name) VALUES (1,1,'wf')");
        UUID wiId = UUID.randomUUID();
        jdbc.update("INSERT INTO workflow_instance (id, tenant_id, project_id, workflow_id, state, " +
                "trigger_type) VALUES (?,1,1,1,'RUNNING','CRON')", wiId);
        jdbc.update("INSERT INTO workflow_node (id, tenant_id, project_id, workflow_id, node_key, name) " +
                "VALUES (1,1,1,1,'A','A'), (2,1,1,1,'B','B')");
        jdbc.update("INSERT INTO workflow_edge (tenant_id, project_id, workflow_id, from_node_id, to_node_id, " +
                "strength) VALUES (1,1,1,1,2,'STRONG')");
        jdbc.update("CREATE TABLE IF NOT EXISTS test_wi (id VARCHAR PRIMARY KEY, wi VARCHAR)");
        jdbc.update("MERGE INTO test_wi KEY(id) VALUES ('wikey', ?)", wiId.toString());
        // A=SUCCESS
        UUID aId = UUID.randomUUID();
        jdbc.update("INSERT INTO task_instance (id, tenant_id, project_id, workflow_instance_id, " +
                "workflow_node_id, biz_date, state, deleted, unmet_deps) " +
                "VALUES (?,1,1,?,?,?,?,0,0)", aId, wiId, 1L, "2026-07-06", "SUCCESS");
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
