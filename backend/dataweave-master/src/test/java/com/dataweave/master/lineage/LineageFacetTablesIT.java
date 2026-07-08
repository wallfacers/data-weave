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
 * 054 US3「左栏分面」真 Neo4j 集成测试——分面「数据源」与「分层」的读侧查询。
 *
 * <p>用<b>生产结构边</b> {@code (:Datasource {id})-[:HAS_TABLE]->(:Table)}（对齐 Neo4jLineageStore.ensureTable），
 * 验证：
 * <ul>
 *   <li>{@code tablesByDatasource} 只返回该数据源该项目的表、按 qualifiedName 排序、attrs 富化 datasourceName/layer
 *       （修 052 占位——展开数据源出真实表而非列，FR-023）。</li>
 *   <li>{@code tablesByLayer} 跨数据源聚合同层表，datasourceName 区分同层跨库（FR-024）。</li>
 *   <li>跨项目零泄漏；空数据源不抛错。</li>
 * </ul>
 */
class LineageFacetTablesIT extends Neo4jTestSupport {

    private static final long T1 = 1L;
    private static final long P1 = 1L;

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
    // 分面「数据源」：tablesByDatasource
    // ═════════════════════════════════════════════════════════════

    @Test
    void tablesByDatasource_returnsRealTablesOfThatDatasourceEnrichedAndOrdered() {
        List<GraphNodeView> tables = querySvc.tablesByDatasource(T1, P1, "ds-a", 0, 100);

        // ds-a 有 3 表（a_dim_user ODS / a_dwd_order DWD / a_ods_log ODS），按 qualifiedName 升序
        assertThat(tables).extracting(GraphNodeView::name)
                .containsExactly("a_dwd_order", "a_ods_log", "a_ods_user");
        // 全是 TABLE（非列——修占位 bug）
        assertThat(tables).allMatch(n -> n.type() == GraphNodeView.NodeType.TABLE);
        // 富化：datasourceName + layer
        var dwd = tables.stream().filter(n -> "a_dwd_order".equals(n.name())).findFirst().orElseThrow();
        assertThat(dwd.attrs().get("datasourceName")).isEqualTo("db-a");
        assertThat(dwd.layer()).isEqualTo("DWD");
        assertThat(dwd.parentId()).isEqualTo("ds-a");
    }

    @Test
    void tablesByDatasource_isolatesToRequestedDatasource() {
        List<GraphNodeView> aTables = querySvc.tablesByDatasource(T1, P1, "ds-a", 0, 100);
        assertThat(aTables).extracting(GraphNodeView::id).doesNotContain("t-b-dwd");

        List<GraphNodeView> bTables = querySvc.tablesByDatasource(T1, P1, "ds-b", 0, 100);
        assertThat(bTables).extracting(GraphNodeView::name).containsExactly("b_dwd_order");
    }

    @Test
    void tablesByDatasource_emptyDatasourceReturnsEmptyNoError() {
        List<GraphNodeView> none = querySvc.tablesByDatasource(T1, P1, "ds-empty", 0, 100);
        assertThat(none).isEmpty();
    }

    @Test
    void datasources_carryTableCountAndNullLayer() {
        // 数据源列表右侧标注改为「表数量」（attrs.tableCount）；layer 置空（原冗余 = 数据源名）。
        List<GraphNodeView> dss = querySvc.datasources(T1, P1, 0, 100);
        // project1：ds-a(db-a)/ds-b(db-b)/ds-empty(db-empty)，按 name 升序
        assertThat(dss).extracting(GraphNodeView::name).containsExactly("db-a", "db-b", "db-empty");
        // layer 不再回填数据源名 → null
        assertThat(dss).allMatch(n -> n.layer() == null);
        // tableCount = 该源下 HAS_TABLE 表数（本项目）：db-a=3、db-b=1、db-empty=0
        var byName = new java.util.HashMap<String, GraphNodeView>();
        dss.forEach(n -> byName.put(n.name(), n));
        assertThat(tableCount(byName.get("db-a"))).isEqualTo(3L);
        assertThat(tableCount(byName.get("db-b"))).isEqualTo(1L);
        assertThat(tableCount(byName.get("db-empty"))).isEqualTo(0L);
    }

    @Test
    void datasources_tableCountDoesNotLeakAcrossProjects() {
        // project2 的 ds-c 下仅 c_dwd_x（1 表）；project1 查不到 ds-c
        List<GraphNodeView> p2 = querySvc.datasources(2L, 2L, 0, 100);
        var dsc = p2.stream().filter(n -> "db-c".equals(n.name())).findFirst().orElseThrow();
        assertThat(tableCount(dsc)).isEqualTo(1L);
        assertThat(querySvc.datasources(T1, P1, 0, 100))
                .extracting(GraphNodeView::name).doesNotContain("db-c");
    }

    private static long tableCount(GraphNodeView node) {
        assertThat(node).as("datasource node present").isNotNull();
        assertThat(node.attrs()).as("attrs present").isNotNull();
        return ((Number) node.attrs().get("tableCount")).longValue();
    }

    @Test
    void tablesByDatasource_doesNotLeakAcrossProjects() {
        // ds-a 属 project1；用 project2 查同 id 应空
        assertThat(querySvc.tablesByDatasource(2L, 2L, "ds-a", 0, 100)).isEmpty();
        // project2 的 ds-c 只在 project2 可见
        assertThat(querySvc.tablesByDatasource(2L, 2L, "ds-c", 0, 100))
                .extracting(GraphNodeView::name).containsExactly("c_dwd_x");
        assertThat(querySvc.tablesByDatasource(T1, P1, "ds-c", 0, 100)).isEmpty();
    }

    // ═════════════════════════════════════════════════════════════
    // 分面「分层」：tablesByLayer（跨数据源聚合）
    // ═════════════════════════════════════════════════════════════

    @Test
    void tablesByLayer_aggregatesAcrossDatasourcesWithDatasourceName() {
        // DWD 层：ds-a.a_dwd_order + ds-b.b_dwd_order（跨库）
        List<GraphNodeView> dwd = querySvc.tablesByLayer(T1, P1, "DWD", 0, 100);
        assertThat(dwd).extracting(GraphNodeView::name).containsExactly("a_dwd_order", "b_dwd_order");
        var dsNames = dwd.stream().map(n -> n.attrs().get("datasourceName")).distinct().toList();
        assertThat(dsNames).containsExactlyInAnyOrder("db-a", "db-b");
    }

    @Test
    void tablesByLayer_odsReturnsOnlyOdsTables() {
        List<GraphNodeView> ods = querySvc.tablesByLayer(T1, P1, "ODS", 0, 100);
        assertThat(ods).extracting(GraphNodeView::name).containsExactly("a_ods_log", "a_ods_user");
        assertThat(ods).allMatch(n -> "ODS".equals(n.layer()));
    }

    @Test
    void tablesByLayer_doesNotLeakAcrossProjects() {
        // project2 DWD 只有 c_dwd_x
        assertThat(querySvc.tablesByLayer(2L, 2L, "DWD", 0, 100))
                .extracting(GraphNodeView::name).containsExactly("c_dwd_x");
    }

    /** 生产结构种子：(:Datasource{id})-[:HAS_TABLE]->(:Table)（去分号单条执行保留变量绑定）。 */
    private static void loadSeedInline(Driver driver) {
        String seed = """
                CREATE (dsa:Datasource {id:'ds-a', dsKey:'ds-a', tenantId:1, projectId:1, name:'db-a'})
                CREATE (dsb:Datasource {id:'ds-b', dsKey:'ds-b', tenantId:1, projectId:1, name:'db-b'})
                CREATE (dsEmpty:Datasource {id:'ds-empty', dsKey:'ds-empty', tenantId:1, projectId:1, name:'db-empty'})
                CREATE (tAUser:Table {id:'t-a-ods-user', tenantId:1, projectId:1, qualifiedName:'a_ods_user', layer:'ODS', datasourceId:'ds-a'})
                CREATE (tALog:Table {id:'t-a-ods-log', tenantId:1, projectId:1, qualifiedName:'a_ods_log', layer:'ODS', datasourceId:'ds-a'})
                CREATE (tADwd:Table {id:'t-a-dwd', tenantId:1, projectId:1, qualifiedName:'a_dwd_order', layer:'DWD', datasourceId:'ds-a'})
                CREATE (tBDwd:Table {id:'t-b-dwd', tenantId:1, projectId:1, qualifiedName:'b_dwd_order', layer:'DWD', datasourceId:'ds-b'})
                CREATE (dsa)-[:HAS_TABLE]->(tAUser)
                CREATE (dsa)-[:HAS_TABLE]->(tALog)
                CREATE (dsa)-[:HAS_TABLE]->(tADwd)
                CREATE (dsb)-[:HAS_TABLE]->(tBDwd)
                CREATE (dsc:Datasource {id:'ds-c', dsKey:'ds-c', tenantId:2, projectId:2, name:'db-c'})
                CREATE (tCDwd:Table {id:'t-c-dwd', tenantId:2, projectId:2, qualifiedName:'c_dwd_x', layer:'DWD', datasourceId:'ds-c'})
                CREATE (dsc)-[:HAS_TABLE]->(tCDwd)
                """;
        try (Session session = driver.session()) {
            session.run(seed.replace(";", "")).consume();
        }
    }
}
