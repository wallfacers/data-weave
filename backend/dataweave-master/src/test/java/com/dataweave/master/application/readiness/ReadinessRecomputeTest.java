package com.dataweave.master.application.readiness;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T020: ReadinessRecompute 单测 — STRONG/WEAK 语义、跨周期逆偏移、幂等、rerun 回退。
 */
@DisplayName("ReadinessRecompute")
class ReadinessRecomputeTest {

    private JdbcTemplate jdbc;
    private ReadinessRecompute recompute;

    @BeforeEach
    void setUp() {
        var ds = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("test-recompute-" + System.currentTimeMillis())
                .build();
        jdbc = new JdbcTemplate(ds);
        ReadinessTestHelper.createMinimalSchema(jdbc);
        recompute = new ReadinessRecompute(jdbc);
    }

    // ─── STRONG 依赖 ─────────────────────────────────────────

    @Test
    @DisplayName("STRONG: 上游 SUCCESS → 下游 unmet=0")
    void strongSuccess() {
        // 创建数据: workflow → A -> B (STRONG)
        seedBasicDag();
        UUID bId = seedTaskInstance("B", "WAITING");
        UUID aId = seedTaskInstance("A", "SUCCESS");

        int unmet = recompute.recomputeSingle(bId);
        assertThat(unmet).isEqualTo(0).as("上游 A 已 SUCCESS，B 应就绪");
    }

    @Test
    @DisplayName("STRONG: 上游 RUNNING → 下游 unmet=1")
    void strongNotReady() {
        seedBasicDag();
        UUID bId = seedTaskInstance("B", "WAITING");
        seedTaskInstance("A", "RUNNING");

        int unmet = recompute.recomputeSingle(bId);
        assertThat(unmet).isEqualTo(1).as("上游 A 仍在 RUNNING，B 有 1 个未满足依赖");
    }

    @Test
    @DisplayName("STRONG: 上游 FAILED → 下游 unmet=1（STRONG 不放行 FAILED）")
    void strongFailed() {
        seedBasicDag();
        UUID bId = seedTaskInstance("B", "WAITING");
        seedTaskInstance("A", "FAILED");

        int unmet = recompute.recomputeSingle(bId);
        assertThat(unmet).isEqualTo(1).as("STRONG 不放行 FAILED，B 未就绪");
    }

    // ─── WEAK 依赖 ───────────────────────────────────────────

    @Test
    @DisplayName("WEAK: 上游 FAILED → 下游 unmet=0")
    void weakFailed() {
        seedDagWithStrength("WEAK");
        UUID bId = seedTaskInstance("B", "WAITING");
        seedTaskInstance("A", "FAILED");

        int unmet = recompute.recomputeSingle(bId);
        assertThat(unmet).isEqualTo(0).as("WEAK 放行 FAILED，B 应就绪");
    }

    @Test
    @DisplayName("WEAK: 上游 RUNNING → 下游 unmet=1")
    void weakRunning() {
        seedDagWithStrength("WEAK");
        UUID bId = seedTaskInstance("B", "WAITING");
        seedTaskInstance("A", "RUNNING");

        int unmet = recompute.recomputeSingle(bId);
        assertThat(unmet).isEqualTo(1);
    }

    // ─── 幂等 ────────────────────────────────────────────────

    @Test
    @DisplayName("幂等：双跑同值")
    void idempotent() {
        seedBasicDag();
        UUID bId = seedTaskInstance("B", "WAITING");
        seedTaskInstance("A", "SUCCESS");

        int first = recompute.recomputeSingle(bId);
        int second = recompute.recomputeSingle(bId);
        assertThat(first).isEqualTo(second).as("同输入应同输出");
    }

    // ─── rerun 回退 ──────────────────────────────────────────

    @Test
    @DisplayName("rerun：上游从 SUCCESS 回到 RUNNING → unmet 加回")
    void rerunIncreasesUnmet() {
        seedBasicDag();
        UUID bId = seedTaskInstance("B", "WAITING");
        UUID aId = seedTaskInstance("A", "SUCCESS");

        int before = recompute.recomputeSingle(bId);
        assertThat(before).isEqualTo(0);

        // rerun A 回到 RUNNING
        jdbc.update("UPDATE task_instance SET state='RUNNING' WHERE id=?", aId);
        int after = recompute.recomputeSingle(bId);
        assertThat(after).isEqualTo(1).as("上游回到 RUNNING，unmet 应加回");
    }

    // ─── 跨周期 ──────────────────────────────────────────────

    @Test
    @DisplayName("跨周期：上周期 SUCCESS → 本周期下游 unmet=0")
    void crossCycleSuccess() {
        seedCrossCycleDag();
        String today = "2026-07-06";
        String yesterday = "2026-07-05";

        // 上周期上游节点 SUCCESS（depend_node_id=10）
        UUID prevId = UUID.randomUUID();
        jdbc.update("INSERT INTO task_instance (id, tenant_id, project_id, workflow_instance_id, " +
                "workflow_node_id, biz_date, state, deleted, unmet_deps) " +
                "VALUES (?,1,1,?,?,?,?,0,0)", prevId, UUID.randomUUID(), 10L, yesterday, "SUCCESS");

        // 本周期的下游 WAITING 实例
        UUID downstreamId = seedCrossCycleDownstream(today);

        int unmet = recompute.recomputeSingle(downstreamId);
        assertThat(unmet).isEqualTo(0).as("上周期 SUCCESS，跨周期依赖应满足");
    }

    @Test
    @DisplayName("跨周期：上周期无 SUCCESS → 本周期下游 unmet=1")
    void crossCycleNotReady() {
        seedCrossCycleDag();
        String today = "2026-07-06";

        UUID downstreamId = seedCrossCycleDownstream(today);

        int unmet = recompute.recomputeSingle(downstreamId);
        assertThat(unmet).isEqualTo(1).as("上周期无 SUCCESS，跨周期未满足");
    }

    // ─── 无依赖直通 ──────────────────────────────────────────

    @Test
    @DisplayName("无上游 edge 的实例 unmet=0（直通就绪）")
    void noUpstreamDirectReady() {
        seedBasicDag();
        UUID soloId = UUID.randomUUID();
        jdbc.update("INSERT INTO task_instance (id, tenant_id, project_id, workflow_instance_id, " +
                "workflow_node_id, state, deleted, unmet_deps) " +
                "VALUES (?,1,1,?,?,?,0,0)", soloId, UUID.randomUUID(), 99L, "WAITING");

        int unmet = recompute.recomputeSingle(soloId);
        assertThat(unmet).isEqualTo(0).as("无上游 edge，直通就绪");
    }

    // ─── 批量重算 ────────────────────────────────────────────

    @Test
    @DisplayName("批量重算：多个下游正确计算")
    void batchRecompute() {
        // setup 独立 DAG: node 100→101, 100→102 (STRONG)
        jdbc.update("INSERT INTO tenants (code, name) VALUES ('t','t')");
        jdbc.update("INSERT INTO projects (tenant_id, code, name) VALUES (1, 'p','p')");
        jdbc.update("INSERT INTO workflow_def (tenant_id, project_id, name) VALUES (1,1,'wf')");
        UUID wiId = UUID.randomUUID();
        jdbc.update("INSERT INTO workflow_instance (id, tenant_id, project_id, workflow_id, state) " +
                "VALUES (?,1,1,1,'RUNNING')", wiId);
        for (long nid : new long[]{100, 101, 102}) {
            jdbc.update("INSERT INTO workflow_node (id, tenant_id, project_id, workflow_id, node_key, name) " +
                    "VALUES (?,1,1,1,?,?)", nid, "bn" + nid, "bnode" + nid);
        }
        jdbc.update("INSERT INTO workflow_edge (tenant_id, project_id, workflow_id, from_node_id, to_node_id, strength) " +
                "VALUES (1,1,1,100,101,'STRONG'), (1,1,1,100,102,'STRONG')");

        UUID aId = UUID.randomUUID();
        UUID bId = UUID.randomUUID();
        UUID cId = UUID.randomUUID();
        jdbc.update("INSERT INTO task_instance (id, tenant_id, project_id, workflow_instance_id, " +
                "workflow_node_id, state, deleted, unmet_deps) " +
                "VALUES (?,1,1,?,?,?,0,0)", aId, wiId, 100L, "RUNNING");
        jdbc.update("INSERT INTO task_instance (id, tenant_id, project_id, workflow_instance_id, " +
                "workflow_node_id, state, deleted, unmet_deps) " +
                "VALUES (?,1,1,?,?,?,0,0)", bId, wiId, 101L, "WAITING");
        jdbc.update("INSERT INTO task_instance (id, tenant_id, project_id, workflow_instance_id, " +
                "workflow_node_id, state, deleted, unmet_deps) " +
                "VALUES (?,1,1,?,?,?,0,0)", cId, wiId, 102L, "WAITING");

        Map<UUID, Integer> result = recompute.recompute(List.of(bId, cId));
        assertThat(result).containsEntry(bId, 1).containsEntry(cId, 1);

        // A → SUCCESS
        jdbc.update("UPDATE task_instance SET state='SUCCESS' WHERE id=?", aId);
        result = recompute.recompute(List.of(bId, cId));
        assertThat(result).containsEntry(bId, 0).containsEntry(cId, 0);
    }

    // ─── 跨周期逆偏移 ────────────────────────────────────────

    @Test
    @DisplayName("逆偏移 LAST_DAY：B' = B + 1day")
    void reverseOffsetLastDay() {
        var result = ReadinessRecompute.reverseOffsetBizDate("2026-07-06", "LAST_DAY");
        assertThat(result).contains("2026-07-07");
    }

    @Test
    @DisplayName("逆偏移 CURRENT_DAY：B' = B")
    void reverseOffsetCurrentDay() {
        var result = ReadinessRecompute.reverseOffsetBizDate("2026-07-06", "CURRENT_DAY");
        assertThat(result).contains("2026-07-06");
    }

    // ─── 辅助 ────────────────────────────────────────────────

    private void seedBasicDag() {
        jdbc.update("INSERT INTO tenants (code, name) VALUES ('test','test')");
        jdbc.update("INSERT INTO projects (tenant_id, code, name) VALUES (1, 'p','p')");
        jdbc.update("INSERT INTO workflow_def (tenant_id, project_id, name) VALUES (1,1,'wf')");
        UUID wiId = UUID.randomUUID();
        jdbc.update("INSERT INTO workflow_instance (id, tenant_id, project_id, workflow_id, state, " +
                "trigger_type) VALUES (?,1,1,1,'RUNNING','CRON')", wiId);
        jdbc.update("INSERT INTO workflow_node (id, tenant_id, project_id, workflow_id, node_key, name) " +
                "VALUES (1,1,1,1,'A','NodeA'), (2,1,1,1,'B','NodeB')");
        jdbc.update("INSERT INTO workflow_edge (tenant_id, project_id, workflow_id, from_node_id, to_node_id, strength) " +
                "VALUES (1,1,1,1,2,'STRONG')");
        // save wiId for seedTaskInstance
        jdbc.update("CREATE TABLE IF NOT EXISTS test_wi (id VARCHAR PRIMARY KEY, wi VARCHAR)");
        jdbc.update("MERGE INTO test_wi KEY(id) VALUES ('wikey', ?)", wiId.toString());
    }

    private void seedDagWithStrength(String strength) {
        seedBasicDag();
        jdbc.update("UPDATE workflow_edge SET strength=?", strength);
    }

    private UUID seedTaskInstance(String nodeKey, String state) {
        UUID id = UUID.randomUUID();
        Long nodeId = jdbc.queryForObject(
                "SELECT id FROM workflow_node WHERE node_key=?", Long.class, nodeKey);
        UUID wiId = UUID.fromString(jdbc.queryForObject("SELECT wi FROM test_wi WHERE id='wikey'", String.class));
        jdbc.update("INSERT INTO task_instance (id, tenant_id, project_id, workflow_instance_id, " +
                "workflow_node_id, state, deleted, unmet_deps) " +
                "VALUES (?,1,1,?,?,?,0,0)", id, wiId, nodeId, state);
        return id;
    }

    private void seedCrossCycleDag() {
        jdbc.update("INSERT INTO tenants (code, name) VALUES ('t','t')");
        jdbc.update("INSERT INTO projects (tenant_id, code, name) VALUES (1, 'p','p')");
        jdbc.update("INSERT INTO workflow_def (tenant_id, project_id, name) VALUES (1,1,'wf')");
        UUID wiId = UUID.randomUUID();
        jdbc.update("INSERT INTO workflow_instance (id, tenant_id, project_id, workflow_id, state, " +
                "trigger_type) VALUES (?,1,1,1,'RUNNING','CRON')", wiId);
        // node 10 = cross-cycle upstream, node 11 = downstream
        jdbc.update("INSERT INTO workflow_node (id, tenant_id, project_id, workflow_id, node_key, name) " +
                "VALUES (10,1,1,1,'DEP','DepNode'), (11,1,1,1,'TGT','TargetNode')");
        // dependency: target workflow=1, node=11 depends on depend_node=10, LAST_DAY offset
        jdbc.update("INSERT INTO workflow_dependency (tenant_id, project_id, workflow_id, node_id, " +
                "depend_workflow_id, depend_node_id, date_offset, earliest_biz_date, enabled, deleted) " +
                "VALUES (1,1,1,11,1,10,'LAST_DAY','2026-01-01',1,0)");
        // save wiId
        jdbc.update("CREATE TABLE IF NOT EXISTS test_wi2 (id VARCHAR PRIMARY KEY, wi VARCHAR)");
        jdbc.update("MERGE INTO test_wi2 KEY(id) VALUES ('wikey2', ?)", wiId.toString());
    }

    private UUID seedCrossCycleDownstream(String bizDate) {
        UUID id = UUID.randomUUID();
        UUID wiId = UUID.fromString(jdbc.queryForObject("SELECT wi FROM test_wi2 WHERE id='wikey2'", String.class));
        // Another workflow instance for the downstream (different from the one containing node 10)
        UUID wiId2 = UUID.randomUUID();
        jdbc.update("INSERT INTO workflow_instance (id, tenant_id, project_id, workflow_id, state, trigger_type) " +
                "VALUES (?,1,1,1,'RUNNING','CRON')", wiId2);
        jdbc.update("INSERT INTO task_instance (id, tenant_id, project_id, workflow_instance_id, " +
                "workflow_node_id, biz_date, state, deleted, unmet_deps) " +
                "VALUES (?,1,1,?,?,?,?,0,0)", id, wiId2, 11L, bizDate, "WAITING");
        return id;
    }
}
