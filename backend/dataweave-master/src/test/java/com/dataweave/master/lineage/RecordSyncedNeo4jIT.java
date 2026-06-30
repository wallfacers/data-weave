package com.dataweave.master.lineage;

import com.dataweave.master.domain.lineage.DatasourceCoord;
import com.dataweave.master.domain.lineage.TableRef;
import com.dataweave.master.infrastructure.lineage.Neo4jLineageStore;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * feature 025 T010：{@link Neo4jLineageStore#recordSynced} 写入 {@code :TaskRun-[:SYNCED]->:Table}，
 * 且 syncSummary 聚合（镜像 {@code LineageQueryService#syncSummary} 的 Cypher，按 {@code r.bizDate=date()} 聚合）
 * 返回真实 SUM——证明 SC-001（syncSummary 不再恒空）。
 *
 * <p>真 neo4j 容器（{@link Neo4jTestSupport}）；bizDate 取容器 {@code date()} 保证聚合命中（时区无关）。
 */
class RecordSyncedNeo4jIT extends Neo4jTestSupport {

    @Test
    void recordSynced_writesSyncedEdge_andSyncSummarySums() {
        Driver driver = newDriver();
        cleanDb(driver);
        Neo4jLineageStore store = newStore();

        long tenant = 1L, project = 1L, taskDefId = 2001L;
        String instanceId = "inst-" + System.nanoTime();
        String bizDate = containerToday(driver);

        DatasourceCoord coord = new DatasourceCoord(tenant, project, null, null, null, "h2mem");
        TableRef ordersClean = new TableRef(coord, "orders_clean", null);
        TableRef ordersDwd = new TableRef(coord, "orders_dwd", null);

        // 两条 statement 各写一表（多 statement 场景，US1/US3）
        store.recordSynced(tenant, project, instanceId, ordersClean, 1000L, null, bizDate, taskDefId);
        store.recordSynced(tenant, project, instanceId, ordersDwd, 500L, null, bizDate, taskDefId);

        // :SYNCED 边 = 2；:TaskRun 单节点带 taskDefId
        assertThat(count(driver, "MATCH (:TaskRun)-[s:SYNCED]->(:Table) RETURN count(s)")).isEqualTo(2L);
        assertThat(count(driver, "MATCH (r:TaskRun {instanceId:'" + instanceId + "'}) RETURN count(r)")).isEqualTo(1L);
        try (Session s = driver.session()) {
            var rec = s.run("MATCH (r:TaskRun {instanceId:$iid}) RETURN r.taskDefId AS td",
                    Map.of("iid", instanceId)).single();
            assertThat(rec.get("td").asLong()).isEqualTo(taskDefId);
        }
        // syncSummary SUM（镜像 LineageQueryService.syncSummary）= 1500
        assertThat(syncedRowsToday(driver, tenant, project)).isEqualTo(1500L);
    }

    @Test
    void recordSynced_idempotentMerge_noDuplication() {
        Driver driver = newDriver();
        cleanDb(driver);
        Neo4jLineageStore store = newStore();
        String bizDate = containerToday(driver);

        DatasourceCoord coord = new DatasourceCoord(1L, 1L, null, null, null, "h2mem");
        TableRef t = new TableRef(coord, "orders_clean", null);

        // 同 (instance, table, bizDate) 重复 recordSynced → MERGE 幂等，不翻倍（SC-003）
        store.recordSynced(1L, 1L, "inst-x", t, 100L, null, bizDate, 1L);
        store.recordSynced(1L, 1L, "inst-x", t, 100L, null, bizDate, 1L);

        assertThat(count(driver, "MATCH (:TaskRun)-[s:SYNCED]->(:Table) RETURN count(s)")).isEqualTo(1L);
        assertThat(syncedRowsToday(driver, 1L, 1L)).isEqualTo(100L);
    }

    private static String containerToday(Driver driver) {
        try (Session s = driver.session()) {
            return s.run("RETURN date() AS today").single().get("today").asString();
        }
    }

    private static long count(Driver driver, String cypher) {
        try (Session session = driver.session()) {
            return session.run(cypher).single().get(0).asLong();
        }
    }

    /** 镜像 LineageQueryService.syncSummary：按 r.bizDate=date() 聚合 SUM(s.rowCount)。 */
    private static long syncedRowsToday(Driver driver, long tenantId, long projectId) {
        try (Session session = driver.session()) {
            var rec = session.run("""
                    MATCH (r:TaskRun)-[s:SYNCED]->(t:Table)
                    WHERE r.tenantId=$t AND r.projectId=$p AND r.bizDate=date()
                    RETURN SUM(s.rowCount) AS syncedRows
                    """, Map.of("t", tenantId, "p", projectId)).single();
            Object val = rec.get("syncedRows").asObject();
            return val instanceof Number n ? n.longValue() : 0L;
        }
    }
}
