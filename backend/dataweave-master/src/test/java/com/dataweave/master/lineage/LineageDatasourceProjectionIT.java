package com.dataweave.master.lineage;

import com.dataweave.master.application.LineageQueryService;
import com.dataweave.master.infrastructure.lineage.Neo4jLineageGraphReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 054 血缘探索器「数据源富化 + 跨源可辨」真 Neo4j 集成测试。
 *
 * <p>覆盖读侧投影契约（contracts/lineage-explorer-v2-api.md §A/§B）：
 * <ul>
 *   <li>neighborhood/upstream/downstream 的 <b>表节点</b> attrs 带 datasourceId + datasourceName，
 *       与图库一致、同库共享（FR-006/007）。</li>
 *   <li>expandColumns 的 <b>列节点</b> 继承所属表数据源；列级 DERIVES_FROM 边 from/to=列 id（FR-012/013）。</li>
 *   <li>search 候选带 datasourceName，同名跨库表可区分（FR-003/SC-005）。</li>
 *   <li>METRIC 候选 datasourceName=null（指标无物理数据源）。</li>
 *   <li>跨项目零泄漏（FR-020）。</li>
 * </ul>
 *
 * <p>种子：跨数据源链 {@code mysql-prod.user → hive-dw.dwd_user → hive-dw.dws_user_1d → pg-bi.rpt_user}
 * + hive 内列级 {@code dwd_user.uid → dws_user_1d.user_id} + 同名跨库（mysql/pg 各一 user）+ 他项目资产。
 *
 * <p>沿用 052 的 inline seed 约定（去分号单条执行以保留变量绑定）。
 */
class LineageDatasourceProjectionIT extends Neo4jTestSupport {

    private static final long T1 = 1L;
    private static final long P1 = 1L;
    private static final long T2 = 2L;
    private static final long P2 = 2L;

    private LineageQueryService querySvc;
    private Driver driver;

    @BeforeEach
    void setUp() {
        driver = newDriver();
        cleanDb(driver);
        loadSeedInline(driver);
        querySvc = new LineageQueryService(new Neo4jLineageGraphReader(driver), noCorrections());
    }

    @SuppressWarnings("unchecked")
    private static org.springframework.beans.factory.ObjectProvider<com.dataweave.master.application.lineage.LineageCorrectionService> noCorrections() {
        org.springframework.beans.factory.ObjectProvider<com.dataweave.master.application.lineage.LineageCorrectionService> p =
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.when(p.getIfAvailable()).thenReturn(null);
        return p;
    }

    // ═════════════════════════════════════════════════════════════
    // 表节点 attrs 富化 datasourceId/datasourceName（FR-006/007）
    // ═════════════════════════════════════════════════════════════

    @Test
    void neighborhood_shouldEnrichTableNodesWithDatasourceIdAndName() {
        // 锚点 dwd_user 跨三库：上游 mysql.user + 下游 hive.dws_user_1d + pg.rpt_user
        LineageGraph result = querySvc.neighborhood(T1, P1, "t-hive-dwd-user", 5,
                GraphNodeView.Granularity.TABLE, null, null, null, null);

        var byId = new java.util.HashMap<String, GraphNodeView>();
        result.nodes().forEach(n -> byId.put(n.id(), n));

        // mysql.user → ds-mysql / mysql-prod
        assertDatasource(byId.get("t-mysql-user"), "ds-mysql", "mysql-prod");
        // hive.dwd_user 与 dws_user_1d 同库共享 ds-hive / hive-dw
        assertDatasource(byId.get("t-hive-dwd-user"), "ds-hive", "hive-dw");
        assertDatasource(byId.get("t-hive-dws-user"), "ds-hive", "hive-dw");
        // pg.rpt_user → ds-pg / pg-bi
        assertDatasource(byId.get("t-pg-rpt-user"), "ds-pg", "pg-bi");
    }

    @Test
    void downstream_shouldCarryDatasourceAcrossCrossDbEdges() {
        LineageGraph result = querySvc.downstream(T1, P1, "t-mysql-user", 5,
                GraphNodeView.Granularity.TABLE, null, null, null, null);
        // 跨库下游节点都带数据源
        var dwd = result.nodes().stream().filter(n -> "t-hive-dwd-user".equals(n.id())).findFirst().orElse(null);
        assertThat(dwd).isNotNull();
        assertDatasource(dwd, "ds-hive", "hive-dw");
        var rpt = result.nodes().stream().filter(n -> "t-pg-rpt-user".equals(n.id())).findFirst().orElse(null);
        assertThat(rpt).isNotNull();
        assertDatasource(rpt, "ds-pg", "pg-bi");
    }

    @Test
    void impact_downstreamNodesCarryDatasource() {
        ImpactResult result = querySvc.impact(T1, P1, "t-mysql-user", 10, 0, 100,
                null, null, null, null);
        var dws = result.downstream().stream().filter(n -> "t-hive-dws-user".equals(n.id())).findFirst().orElse(null);
        assertThat(dws).isNotNull();
        assertDatasource(dws, "ds-hive", "hive-dw");
    }

    // ═════════════════════════════════════════════════════════════
    // 列节点继承数据源 + 列级边（FR-012/013）
    // ═════════════════════════════════════════════════════════════

    @Test
    void expandColumns_shouldEnrichColumnsWithInheritedDatasourceAndColumnEdges() {
        // 展开 hive.dwd_user：本表列 uid + 邻接列 dws_user_1d.user_id（经 DERIVES_FROM）
        LineageGraph result = querySvc.expandColumns(T1, P1, "t-hive-dwd-user");

        // 本表列 col-dwd-uid 继承 dwd_user 的数据源 ds-hive/hive-dw
        var uid = result.nodes().stream().filter(n -> "col-dwd-uid".equals(n.id())).findFirst().orElse(null);
        assertThat(uid).isNotNull();
        assertThat(uid.parentId()).isEqualTo("t-hive-dwd-user");
        assertDatasource(uid, "ds-hive", "hive-dw");

        // 邻接列 col-dws-user-id 继承其所属表 dws_user_1d 的数据源
        var userId = result.nodes().stream().filter(n -> "col-dws-user-id".equals(n.id())).findFirst().orElse(null);
        assertThat(userId).isNotNull();
        assertThat(userId.parentId()).isEqualTo("t-hive-dws-user");
        assertDatasource(userId, "ds-hive", "hive-dw");

        // 列级派生边：from/to 均为列 id，granularity=COLUMN
        assertThat(result.edges()).anySatisfy(e -> {
            assertThat(e.granularity()).isEqualTo(GraphNodeView.Granularity.COLUMN);
            assertThat(e.from()).isEqualTo("col-dwd-uid");
            assertThat(e.to()).isEqualTo("col-dws-user-id");
        });
    }

    @Test
    void columns_shouldEnrichWithInheritedDatasource() {
        List<GraphNodeView> cols = querySvc.columns(T1, P1, "t-mysql-user", 0, 100);
        // 即便本例 mysql.user 未建列，返回空也不应抛错；改用有列的 hive 表断言富化
        List<GraphNodeView> hiveCols = querySvc.columns(T1, P1, "t-hive-dwd-user", 0, 100);
        assertThat(hiveCols).extracting(GraphNodeView::id).contains("col-dwd-uid");
        var uid = hiveCols.stream().filter(n -> "col-dwd-uid".equals(n.id())).findFirst().orElseThrow();
        assertDatasource(uid, "ds-hive", "hive-dw");
        // 顺便保证 mysql.user 无列时不抛错
        assertThat(cols).isEmpty();
    }

    // ═════════════════════════════════════════════════════════════
    // search 候选 datasourceName + 同名跨库区分 + METRIC 无（FR-003/SC-005）
    // ═════════════════════════════════════════════════════════════

    @Test
    void search_shouldEnrichCandidatesWithDatasourceNameAndDisambiguateCrossDb() {
        // "user" 命中两库的同名表 user（mysql-prod + pg-bi），datasourceName 必须可区分
        List<SearchCandidate> results = querySvc.search(T1, P1, "user", null, 0, 100);

        var userCandidates = results.stream().filter(c -> "user".equals(c.name())).toList();
        assertThat(userCandidates).hasSizeGreaterThanOrEqualTo(2);
        assertThat(userCandidates).allMatch(c -> "TABLE".equals(c.type()));
        // 两个同名 user 来自不同数据源 → datasourceName 不同（同名跨库可辨）
        var dsNames = userCandidates.stream().map(SearchCandidate::datasourceName).distinct().toList();
        assertThat(dsNames).containsExactlyInAnyOrder("mysql-prod", "pg-bi");
        // datasource(id) 同样区分
        assertThat(userCandidates.stream().map(SearchCandidate::datasource).distinct())
                .containsExactlyInAnyOrder("ds-mysql", "ds-pg");
    }

    @Test
    void search_metricCandidateHasNullDatasourceName() {
        List<SearchCandidate> results = querySvc.search(T1, P1, "active_user_metric", null, 0, 100);
        assertThat(results).extracting(SearchCandidate::name).contains("active_user_metric");
        var metric = results.stream().filter(c -> "active_user_metric".equals(c.name())).findFirst().orElseThrow();
        assertThat(metric.type()).isEqualTo("METRIC");
        // 指标无物理数据源 → datasourceName null（@JsonInclude NON_NULL 时缺省）
        assertThat(metric.datasourceName()).isNull();
    }

    // ═════════════════════════════════════════════════════════════
    // 跨项目零泄漏（FR-020）
    // ═════════════════════════════════════════════════════════════

    @Test
    void searchAndNeighborhood_shouldNotLeakAcrossProjects() {
        // tenant1 搜 user：不得返回 tenant2 的 t-t2-user
        List<SearchCandidate> t1 = querySvc.search(T1, P1, "user", null, 0, 100);
        assertThat(t1).extracting(SearchCandidate::id).doesNotContain("t-t2-user");

        // tenant2 搜 user：只返回自己的 t-t2-user，不含 tenant1 资产
        List<SearchCandidate> t2 = querySvc.search(T2, P2, "user", null, 0, 100);
        assertThat(t2).extracting(SearchCandidate::id).contains("t-t2-user");
        assertThat(t2).extracting(SearchCandidate::id)
                .doesNotContain("t-mysql-user", "t-hive-dwd-user", "t-pg-user");

        // 邻域同样不跨项目
        LineageGraph t1Nb = querySvc.neighborhood(T1, P1, "t-hive-dwd-user", 5,
                GraphNodeView.Granularity.TABLE, null, null, null, null);
        assertThat(t1Nb.nodes()).extracting(GraphNodeView::id).doesNotContain("t-t2-user");
    }

    // ═════════════════════════════════════════════════════════════
    // Helpers
    // ═════════════════════════════════════════════════════════════

    private static void assertDatasource(GraphNodeView node, String expectedId, String expectedName) {
        assertThat(node).as("node present").isNotNull();
        assertThat(node.attrs()).as("node attrs present").isNotNull();
        assertThat(node.attrs().get("datasourceId"))
                .as("datasourceId for %s", node.id()).isEqualTo(expectedId);
        assertThat(node.attrs().get("datasourceName"))
                .as("datasourceName for %s", node.id()).isEqualTo(expectedName);
    }

    /** 跨数据源 + 列级血缘种子（单条去分号执行，保留变量绑定，沿用 ExplorerE2EIT 约定）。 */
    private static void loadSeedInline(Driver driver) {
        String seed = """
                CREATE (dsMysql:Datasource {id: 'ds-mysql', tenantId: 1, projectId: 1, name: 'mysql-prod'})
                CREATE (dsHive:Datasource {id: 'ds-hive', tenantId: 1, projectId: 1, name: 'hive-dw'})
                CREATE (dsPg:Datasource {id: 'ds-pg', tenantId: 1, projectId: 1, name: 'pg-bi'})
                CREATE (tMysqlUser:Table {id: 't-mysql-user', tenantId: 1, projectId: 1, qualifiedName: 'user', layer: 'ODS', datasourceId: 'ds-mysql'})
                CREATE (tHiveDwd:Table {id: 't-hive-dwd-user', tenantId: 1, projectId: 1, qualifiedName: 'dwd_user', layer: 'DWD', datasourceId: 'ds-hive'})
                CREATE (tHiveDws:Table {id: 't-hive-dws-user', tenantId: 1, projectId: 1, qualifiedName: 'dws_user_1d', layer: 'DWS', datasourceId: 'ds-hive'})
                CREATE (tPgRpt:Table {id: 't-pg-rpt-user', tenantId: 1, projectId: 1, qualifiedName: 'rpt_user', layer: 'ADS', datasourceId: 'ds-pg'})
                CREATE (tPgUser:Table {id: 't-pg-user', tenantId: 1, projectId: 1, qualifiedName: 'user', layer: 'ODS', datasourceId: 'ds-pg'})
                CREATE (tMysqlUser)-[:HAS_DATASOURCE]->(dsMysql)
                CREATE (tHiveDwd)-[:HAS_DATASOURCE]->(dsHive)
                CREATE (tHiveDws)-[:HAS_DATASOURCE]->(dsHive)
                CREATE (tPgRpt)-[:HAS_DATASOURCE]->(dsPg)
                CREATE (tPgUser)-[:HAS_DATASOURCE]->(dsPg)
                CREATE (tMysqlUser)-[:FLOWS_TO {taskDefId: 100, confidence: 'CONFIRMED', source: 'SQL_PARSED'}]->(tHiveDwd)
                CREATE (tHiveDwd)-[:FLOWS_TO {taskDefId: 101, confidence: 'CONFIRMED', source: 'SQL_PARSED'}]->(tHiveDws)
                CREATE (tHiveDws)-[:FLOWS_TO {taskDefId: 102, confidence: 'UNVERIFIED', source: 'FORM'}]->(tPgRpt)
                CREATE (cDwdUid:Column {id: 'col-dwd-uid', tenantId: 1, projectId: 1, name: 'uid', dataType: 'BIGINT', ordinal: 1, tableKey: 't-hive-dwd-user'})
                CREATE (cDwsUserId:Column {id: 'col-dws-user-id', tenantId: 1, projectId: 1, name: 'user_id', dataType: 'BIGINT', ordinal: 1, tableKey: 't-hive-dws-user'})
                CREATE (tHiveDwd)-[:HAS_COLUMN]->(cDwdUid)
                CREATE (tHiveDws)-[:HAS_COLUMN]->(cDwsUserId)
                CREATE (cDwdUid)-[:DERIVES_FROM {taskDefId: 101, transform: 'DIRECT', confidence: 'CONFIRMED', source: 'SQL_PARSED'}]->(cDwsUserId)
                CREATE (task101:Task {id: 'task-101', tenantId: 1, projectId: 1, name: 'etl_mysql_to_hive', taskDefId: 101})
                CREATE (task101)-[:WRITES]->(tHiveDwd)
                CREATE (metricUser:Metric {id: 'metric-user', tenantId: 1, projectId: 1, name: 'active_user_metric', metricType: 'DERIVED'})
                CREATE (metricUser)-[:COMPUTED_FROM]->(tMysqlUser)
                CREATE (dsT2:Datasource {id: 'ds-t2', tenantId: 2, projectId: 2, name: 'other-db'})
                CREATE (tT2User:Table {id: 't-t2-user', tenantId: 2, projectId: 2, qualifiedName: 'user', layer: 'ODS', datasourceId: 'ds-t2'})
                CREATE (tT2User)-[:HAS_DATASOURCE]->(dsT2)
                """;
        try (Session session = driver.session()) {
            session.run(seed.replace(";", "")).consume();
        }
    }
}
