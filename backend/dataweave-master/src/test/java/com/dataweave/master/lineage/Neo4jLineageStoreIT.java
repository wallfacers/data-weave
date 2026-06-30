package com.dataweave.master.lineage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.dataweave.master.domain.lineage.ColumnEdge;
import com.dataweave.master.domain.lineage.Confidence;
import com.dataweave.master.domain.lineage.DatasourceCoord;
import com.dataweave.master.domain.lineage.Direction;
import com.dataweave.master.domain.lineage.IoEdge;
import com.dataweave.master.domain.lineage.MetricEdge;
import com.dataweave.master.domain.lineage.Source;
import com.dataweave.master.domain.lineage.TableRef;
import com.dataweave.master.domain.lineage.Transform;
import com.dataweave.master.infrastructure.lineage.Neo4jLineageStore;
import com.dataweave.master.infrastructure.lineage.Neo4jSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;

/**
 * {@link Neo4jLineageStore} 集成测试（Testcontainers neo4j 真容器）。
 *
 * <p>覆盖：T011 recordTaskIo 核心入图、T012 replace-per-task 幂等、T013 ColumnEdge 写入。
 * US3 去重（T022/T023）、指标血缘（T025）、种子幂等（T032）用例在后续追加。
 */
class Neo4jLineageStoreIT extends Neo4jTestSupport {

    private Driver driver;
    private Neo4jLineageStore store;

    private static final DatasourceCoord COORD = new DatasourceCoord(
            1L, 1L, "10.0.0.1", 5432, "warehouse", null);

    @BeforeEach
    void setUp() {
        driver = newDriver();
        new Neo4jSchemaInitializer(driver).initialize();
        cleanDb(driver);
        store = new Neo4jLineageStore(driver);
    }

    // ── T011：建任务即把血缘落入 neo4j（核心） ────────────────────────────────

    @Test
    @DisplayName("单任务两表 → :Table×2 + READS/WRITES + FLOWS_TO 派生边")
    void recordTaskIoWritesTablesAndIoEdges() {
        IoEdge read = edge("ods_order", "ODS", Direction.READS);
        IoEdge write = edge("dwd_order", "DWD", Direction.WRITES);

        store.recordTaskIo(1L, 1L, 9001L, 1, "订单明细加工", List.of(read, write), List.of());

        assertThat(count("MATCH (t:Table) WHERE t.qualifiedName IN ['ods_order','dwd_order'] RETURN count(t) AS c"))
                .as(":Table 节点数").isEqualTo(2L);
        assertThat(count("MATCH (:Datasource {database:'warehouse'})-[:HAS_TABLE]->(:Table) RETURN count(*) AS c"))
                .as("HAS_TABLE 边数").isEqualTo(2L);
        assertThat(count("MATCH (:Task {taskDefId:9001})-[:READS]->(:Table {qualifiedName:'ods_order'}) RETURN count(*) AS c"))
                .as("READS 边").isEqualTo(1L);
        assertThat(count("MATCH (:Task {taskDefId:9001})-[:WRITES]->(:Table {qualifiedName:'dwd_order'}) RETURN count(*) AS c"))
                .as("WRITES 边").isEqualTo(1L);
        assertThat(count("MATCH (:Table {qualifiedName:'ods_order'})-[:FLOWS_TO]->(:Table {qualifiedName:'dwd_order'}) RETURN count(*) AS c"))
                .as("FLOWS_TO 派生边").isEqualTo(1L);
        // :Task 镜像节点带 tenantId/projectId（隔离）
        assertThat(count("MATCH (t:Task {taskDefId:9001}) WHERE t.tenantId=1 AND t.projectId=1 RETURN count(t) AS c"))
                .as(":Task 镜像带租户/项目").isEqualTo(1L);
    }

    // ── T012：replace-per-task 幂等（SC-003） ─────────────────────────────────

    @Test
    @DisplayName("同任务 recordTaskIo 两次 → 边集合一致、无翻倍、无残留陈边")
    void recordTaskIoIsIdempotentPerTask() {
        IoEdge read = edge("ods_order", "ODS", Direction.READS);
        IoEdge write = edge("dwd_order", "DWD", Direction.WRITES);
        List<IoEdge> edges = List.of(read, write);

        store.recordTaskIo(1L, 1L, 9001L, 1, "etl", edges, List.of());
        store.recordTaskIo(1L, 1L, 9001L, 1, "etl", edges, List.of());

        assertThat(count("MATCH (:Task {taskDefId:9001})-[r:READS|WRITES]->() RETURN count(r) AS c"))
                .as("两次记录后读写边不翻倍").isEqualTo(2L);
        assertThat(count("MATCH (:Task {taskDefId:9001})-[:READS]->(:Table) RETURN count(*) AS c")).isEqualTo(1L);
        assertThat(count("MATCH (:Task {taskDefId:9001})-[:WRITES]->(:Table) RETURN count(*) AS c")).isEqualTo(1L);
        assertThat(count("MATCH ()-[f:FLOWS_TO]->() WHERE f.taskDefId=9001 RETURN count(f) AS c"))
                .as("FLOWS_TO 不翻倍").isEqualTo(1L);
    }

    @Test
    @DisplayName("改写后再记录 → 旧边整体替换，无残留陈边（spec US1 验收 #2）")
    void recordTaskIoReplacesStaleEdges() {
        // 第一次：READ ods_order / WRITE dwd_order
        store.recordTaskIo(1L, 1L, 9001L, 1, "etl",
                List.of(edge("ods_order", "ODS", Direction.READS), edge("dwd_order", "DWD", Direction.WRITES)),
                List.of());
        // 第二次：READ 改为 ods_user / WRITE 仍 dwd_order
        store.recordTaskIo(1L, 1L, 9001L, 1, "etl",
                List.of(edge("ods_user", "ODS", Direction.READS), edge("dwd_order", "DWD", Direction.WRITES)),
                List.of());

        assertThat(count("MATCH (:Task {taskDefId:9001})-[:READS]->(:Table {qualifiedName:'ods_order'}) RETURN count(*) AS c"))
                .as("旧 READS 陈边已删").isEqualTo(0L);
        assertThat(count("MATCH (:Task {taskDefId:9001})-[:READS]->(:Table {qualifiedName:'ods_user'}) RETURN count(*) AS c"))
                .as("新 READS 边已建").isEqualTo(1L);
        assertThat(count("MATCH (:Table {qualifiedName:'ods_order'})-[:FLOWS_TO]->() RETURN count(*) AS c"))
                .as("旧 FLOWS_TO 陈边已删").isEqualTo(0L);
        assertThat(count("MATCH (:Task {taskDefId:9001})-[r:READS|WRITES]->() RETURN count(r) AS c"))
                .as("总读写边仍为 2").isEqualTo(2L);
    }

    // ── T013：ColumnEdge 写入（FR-011） ───────────────────────────────────────

    @Test
    @DisplayName("传入 ColumnEdge → :Column 节点 + HAS_COLUMN + DERIVES_FROM {taskDefId,transform}")
    void recordTaskIoWritesColumnEdges() {
        IoEdge read = edge("ods_order", "ODS", Direction.READS);
        IoEdge write = edge("dwd_order", "DWD", Direction.WRITES);
        ColumnEdge col = new ColumnEdge(
                read.table(), "order_id",
                write.table(), "order_id",
                Transform.DIRECT, Confidence.CONFIRMED);

        store.recordTaskIo(1L, 1L, 9001L, 1, "etl", List.of(read, write), List.of(col));

        assertThat(count("MATCH (:Table {qualifiedName:'ods_order'})-[:HAS_COLUMN]->(:Column {name:'order_id'}) RETURN count(*) AS c"))
                .as("源表 HAS_COLUMN").isEqualTo(1L);
        assertThat(count("MATCH (:Table {qualifiedName:'dwd_order'})-[:HAS_COLUMN]->(:Column {name:'order_id'}) RETURN count(*) AS c"))
                .as("目标表 HAS_COLUMN").isEqualTo(1L);
        assertThat(count("MATCH (a:Column {name:'order_id'})-[d:DERIVES_FROM]->(b:Column {name:'order_id'}) "
                + "WHERE d.taskDefId=9001 AND d.transform='DIRECT' RETURN count(d) AS c"))
                .as("DERIVES_FROM 带 taskDefId/transform").isEqualTo(1L);
    }

    // ── T022/T023：数据源去重（US3，SC-002） ───────────────────────────────────

    @Test
    @DisplayName("同 (ip,port,database) 被多任务引用 → :Datasource 唯一、目标 :Table 唯一")
    void datasourceDedupSamePhysicalDbSingleNode() {
        // 同物理坐标（凭据不在 coord，故不同凭据归一同一 dsKey）
        DatasourceCoord coord = new DatasourceCoord(1L, 1L, "10.0.0.1", 5432, "warehouse", null);
        IoEdge e1 = new IoEdge(new TableRef(coord, "dwd_order", "DWD"), Direction.WRITES,
                Source.SQL_PARSED, Confidence.CONFIRMED);
        IoEdge e2 = new IoEdge(new TableRef(coord, "dwd_order", "DWD"), Direction.READS,
                Source.SQL_PARSED, Confidence.CONFIRMED);
        store.recordTaskIo(1L, 1L, 9101L, 1, "t1", List.of(e1), List.of());
        store.recordTaskIo(1L, 1L, 9102L, 1, "t2", List.of(e2), List.of());

        assertThat(count("MATCH (d:Datasource {ip:'10.0.0.1',port:5432,database:'warehouse'}) RETURN count(d) AS c"))
                .as(":Datasource 唯一").isEqualTo(1L);
        assertThat(count("MATCH (t:Table {qualifiedName:'dwd_order'}) RETURN count(t) AS c"))
                .as("目标 :Table 唯一").isEqualTo(1L);
    }

    @Test
    @DisplayName("同 ip/port 不同 database → 两个不同 :Datasource；缺坐标 → 降级身份仍唯一")
    void datasourceDedupDifferentDbAndFallback() {
        DatasourceCoord wh = new DatasourceCoord(1L, 1L, "10.0.0.1", 5432, "warehouse", null);
        DatasourceCoord an = new DatasourceCoord(1L, 1L, "10.0.0.1", 5432, "analytics", null);
        store.recordTaskIo(1L, 1L, 9201L, 1, "t1",
                List.of(new IoEdge(new TableRef(wh, "a", "DWD"), Direction.WRITES, Source.SQL_PARSED, Confidence.CONFIRMED)),
                List.of());
        store.recordTaskIo(1L, 1L, 9202L, 1, "t2",
                List.of(new IoEdge(new TableRef(an, "b", "DWD"), Direction.WRITES, Source.SQL_PARSED, Confidence.CONFIRMED)),
                List.of());
        assertThat(count("MATCH (d:Datasource {ip:'10.0.0.1',port:5432}) RETURN count(d) AS c"))
                .as("同 ip/port 不同 database → 2 个 :Datasource").isEqualTo(2L);

        // 缺连接坐标 → 降级身份 datasource:<name>，重复记录仍唯一
        DatasourceCoord miss = new DatasourceCoord(1L, 1L, null, null, null, "local-files");
        store.recordTaskIo(1L, 1L, 9203L, 1, "t3",
                List.of(new IoEdge(new TableRef(miss, "logs", null), Direction.WRITES, Source.SQL_PARSED, Confidence.CONFIRMED)),
                List.of());
        store.recordTaskIo(1L, 1L, 9204L, 1, "t4",
                List.of(new IoEdge(new TableRef(miss, "logs2", null), Direction.WRITES, Source.SQL_PARSED, Confidence.CONFIRMED)),
                List.of());
        assertThat(count("MATCH (d:Datasource {dsKey:'1|datasource:local-files'}) RETURN count(d) AS c"))
                .as("缺坐标降级身份唯一").isEqualTo(1L);
    }

    // ── T025：指标血缘迁图（FR-008） ───────────────────────────────────────────

    @Test
    @DisplayName("recordMetricLineage → :Metric-[:COMPUTED_FROM]->:Table")
    void recordMetricLineageWritesComputedFrom() {
        // 先经任务路径建表（metric 下游表通常已存在）
        DatasourceCoord coord = new DatasourceCoord(1L, 1L, "10.0.0.1", 5432, "warehouse", null);
        store.recordTaskIo(1L, 1L, 9301L, 1, "etl",
                List.of(new IoEdge(new TableRef(coord, "dwd_order", "DWD"), Direction.WRITES,
                        Source.SQL_PARSED, Confidence.CONFIRMED)), List.of());

        store.recordMetricLineage(new MetricEdge(1L, 1L, "ATOMIC", 1L, "order_count", "TABLE", "dwd_order"));

        assertThat(count("MATCH (m:Metric {metricType:'ATOMIC',metricId:1}) RETURN count(m) AS c"))
                .as(":Metric 节点").isEqualTo(1L);
        assertThat(count("MATCH (m:Metric {metricType:'ATOMIC',metricId:1})-[:COMPUTED_FROM]->(:Table {qualifiedName:'dwd_order'}) RETURN count(*) AS c"))
                .as("COMPUTED_FROM 到下游表").isEqualTo(1L);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private IoEdge edge(String table, String layer, Direction dir) {
        return new IoEdge(new TableRef(COORD, table, layer), dir, Source.SQL_PARSED, Confidence.CONFIRMED);
    }

    private long count(String cypher) {
        try (Session session = driver.session()) {
            return session.run(cypher, Map.of()).single().get("c").asLong();
        }
    }
}
