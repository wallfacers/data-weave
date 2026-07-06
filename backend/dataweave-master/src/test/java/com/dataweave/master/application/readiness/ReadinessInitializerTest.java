package com.dataweave.master.application.readiness;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T019: ReadinessInitializer 单测 — 物化初值只计未满足依赖（C1 关键）。
 */
@DisplayName("ReadinessInitializer")
class ReadinessInitializerTest {

    private JdbcTemplate jdbc;
    private ReadinessInitializer initializer;

    @BeforeEach
    void setUp() {
        var ds = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("test-init-" + System.currentTimeMillis())
                .build();
        jdbc = new JdbcTemplate(ds);
        ReadinessTestHelper.createMinimalSchema(jdbc);
        initializer = new ReadinessInitializer(new ReadinessRecompute(jdbc));
    }

    @Test
    @DisplayName("C1 回归：上游已 SUCCESS 再物化 B → B 初值不计 A（unmet=0）")
    void upstreamAlreadySuccessOnMaterialize() {
        // A 已完成 SUCCESS
        seedBasicDag("STRONG");
        UUID aId = seedInstance("A", "SUCCESS");
        // 现在才物化 B
        UUID bId = seedInstance("B", "WAITING");

        int unmet = initializer.initializeOne(bId);
        assertThat(unmet).isEqualTo(0)
                .as("C1：A 已 SUCCESS，物化 B 时已满足依赖不计入初值");
    }

    @Test
    @DisplayName("上游未完成时物化 → unmet=1")
    void upstreamRunningOnMaterialize() {
        seedBasicDag("STRONG");
        seedInstance("A", "RUNNING");
        UUID bId = seedInstance("B", "WAITING");

        int unmet = initializer.initializeOne(bId);
        assertThat(unmet).isEqualTo(1).as("A 仍在 RUNNING，B 有 1 个未满足");
    }

    @Test
    @DisplayName("无上游依赖直通 → unmet=0")
    void noUpstreamDirectReady() {
        seedBasicDag("STRONG");
        // 创建一个不在 DAG 中的独立实例（workflow_node_id 无对应 edge）
        UUID soloId = UUID.randomUUID();
        jdbc.update("INSERT INTO task_instance (id, tenant_id, project_id, workflow_instance_id, " +
                "workflow_node_id, state, deleted, unmet_deps) " +
                "VALUES (?,1,1,?,?,?,0,0)", soloId, UUID.randomUUID(), 99L, "WAITING");

        int unmet = initializer.initializeOne(soloId);
        assertThat(unmet).isEqualTo(0).as("无上游 edge，直通就绪");
    }

    @Test
    @DisplayName("跨周期：上周期已 SUCCESS → 物化初值不计")
    void crossCycleAlreadySuccess() {
        // 上周期 SUCCESS 已存在
        UUID prevId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (code, name) VALUES ('t','t')");
        jdbc.update("INSERT INTO projects (tenant_id, code, name) VALUES (1,'p','p')");
        jdbc.update("INSERT INTO workflow_def (tenant_id, project_id, name) VALUES (1,1,'wf')");
        jdbc.update("INSERT INTO workflow_node (id, tenant_id, project_id, workflow_id, node_key, name) " +
                "VALUES (10,1,1,1,'DEP','Dep'), (11,1,1,1,'TGT','Tgt')");
        jdbc.update("INSERT INTO workflow_dependency (tenant_id, project_id, workflow_id, node_id, " +
                "depend_workflow_id, depend_node_id, date_offset, earliest_biz_date, enabled, deleted) " +
                "VALUES (1,1,1,11,1,10,'LAST_DAY','2026-01-01',1,0)");
        jdbc.update("INSERT INTO task_instance (id, tenant_id, project_id, workflow_instance_id, " +
                "workflow_node_id, biz_date, state, deleted, unmet_deps) " +
                "VALUES (?,1,1,?,?,?,?,0,0)", prevId, UUID.randomUUID(), 10L, "2026-07-05", "SUCCESS");

        // 物化本周期下游
        UUID wiId = UUID.randomUUID();
        jdbc.update("INSERT INTO workflow_instance (id, tenant_id, project_id, workflow_id, state, " +
                "trigger_type) VALUES (?,1,1,1,'RUNNING','CRON')", wiId);
        UUID bId = UUID.randomUUID();
        jdbc.update("INSERT INTO task_instance (id, tenant_id, project_id, workflow_instance_id, " +
                "workflow_node_id, biz_date, state, deleted, unmet_deps) " +
                "VALUES (?,1,1,?,?,?,?,0,0)", bId, wiId, 11L, "2026-07-06", "WAITING");

        int unmet = initializer.initializeOne(bId);
        assertThat(unmet).isEqualTo(0).as("上周期已 SUCCESS，跨周期依赖已满足，不计入初值");
    }

    @Test
    @DisplayName("首周期豁免：bizDate < earliest_biz_date → 跨周期不计")
    void firstCycleExempt() {
        // setup dependency with earliest_biz_date=2026-07-01
        jdbc.update("INSERT INTO tenants (code, name) VALUES ('t','t')");
        jdbc.update("INSERT INTO projects (tenant_id, code, name) VALUES (1,'p','p')");
        jdbc.update("INSERT INTO workflow_def (tenant_id, project_id, name) VALUES (1,1,'wf')");
        jdbc.update("INSERT INTO workflow_node (id, tenant_id, project_id, workflow_id, node_key, name) " +
                "VALUES (10,1,1,1,'DEP','Dep'), (11,1,1,1,'TGT','Tgt')");
        jdbc.update("INSERT INTO workflow_dependency (tenant_id, project_id, workflow_id, node_id, " +
                "depend_workflow_id, depend_node_id, date_offset, earliest_biz_date, enabled, deleted) " +
                "VALUES (1,1,1,11,1,10,'LAST_DAY','2026-07-01',1,0)");

        UUID wiId = UUID.randomUUID();
        jdbc.update("INSERT INTO workflow_instance (id, tenant_id, project_id, workflow_id, state, " +
                "trigger_type) VALUES (?,1,1,1,'RUNNING','CRON')", wiId);
        UUID bId = UUID.randomUUID();
        // bizDate=2026-06-30 < earliest_biz_date=2026-07-01 → 首周期豁免
        jdbc.update("INSERT INTO task_instance (id, tenant_id, project_id, workflow_instance_id, " +
                "workflow_node_id, biz_date, state, deleted, unmet_deps) " +
                "VALUES (?,1,1,?,?,?,?,0,0)", bId, wiId, 11L, "2026-06-30", "WAITING");

        int unmet = initializer.initializeOne(bId);
        assertThat(unmet).isEqualTo(0).as("首周期豁免，跨周期依赖不计");
    }

    @Test
    @DisplayName("非 CRON 实例忽略跨周期 → unmet 只看 DAG 内")
    void nonCronIgnoresCrossCycle() {
        // DAG 内 A→B STRONG，A RUNNING
        seedBasicDag("STRONG");
        seedInstance("A", "RUNNING");

        // 物化 B 为非 CRON
        UUID wiId = UUID.fromString(jdbc.queryForObject("SELECT wi FROM test_wi WHERE id='wikey'", String.class));
        jdbc.update("UPDATE workflow_instance SET trigger_type='MANUAL' WHERE id=?", wiId);
        UUID bId = seedInstance("B", "WAITING");

        int unmet = initializer.initializeOne(bId);
        assertThat(unmet).isEqualTo(1).as("非 CRON，只有 DAG 内上游未满足（1个）");
    }

    @Test
    @DisplayName("重算异常 → fail-closed 置未就绪哨兵（非 0，绝不提前认领）")
    void recomputeFailureIsFailClosed() {
        // 注入一个 recomputeSingle 必抛的 ReadinessRecompute
        ReadinessInitializer failing = new ReadinessInitializer(new ReadinessRecompute(jdbc) {
            @Override
            public int recomputeSingle(UUID instanceId) {
                throw new RuntimeException("boom");
            }
        });
        UUID id = UUID.randomUUID();
        var map = failing.initialize(List.of(id));
        assertThat(map.get(id))
                .as("fail-closed：出错须置正值哨兵（未就绪），不得回退 0（就绪）")
                .isEqualTo(Integer.MAX_VALUE)
                .isGreaterThan(0);
    }

    // ─── 辅助 ────────────────────────────────────────────────

    private void seedBasicDag(String strength) {
        jdbc.update("INSERT INTO tenants (code, name) VALUES ('test','test')");
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
        Long nodeId = jdbc.queryForObject(
                "SELECT id FROM workflow_node WHERE node_key=?", Long.class, nodeKey);
        UUID wiId = UUID.fromString(jdbc.queryForObject("SELECT wi FROM test_wi WHERE id='wikey'", String.class));
        jdbc.update("INSERT INTO task_instance (id, tenant_id, project_id, workflow_instance_id, " +
                "workflow_node_id, state, deleted, unmet_deps) " +
                "VALUES (?,1,1,?,?,?,0,0)", id, wiId, nodeId, state);
        return id;
    }
}
