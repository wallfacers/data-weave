package com.dataweave.master.lineage;

import com.dataweave.master.application.LineageQueryService;
import com.dataweave.master.infrastructure.lineage.Neo4jLineageGraphReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 052 血缘图探索器 Neo4j 集成测试（真 neo4j 容器 / NEO4J_TEST_URI）。
 *
 * <p>覆盖：neighborhood 双向带边、search 中缀命中+跨项目零泄漏、impact edges 闭合+
 * reachableTotal 与分页解耦、paths 多路径去重+无路径、attrs 富化字段、过滤生效。
 *
 * <p>种子：seed-lineage.cypher（扩：Task-[:WRITES]->Table + 更多 layer/name）。
 */
class LineageGraphExplorerE2EIT extends Neo4jTestSupport {

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
        // Load seed data（逐条执行——Neo4j driver 要求一次一条语句）
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
    // T017: neighborhood 双向带边
    // ═════════════════════════════════════════════════════════════

    @Test
    void neighborhood_shouldReturnBidirectionalNodesAndEdges() {
        // t-b (dwd_orders_clean) has t-a→t-b and t-b→t-c→t-d and t-b→t-e
        LineageGraph result = querySvc.neighborhood(T1, P1, "t-b", 5,
                GraphNodeView.Granularity.TABLE, null, null, null, null);

        // 节点：上游 t-a + 下游 t-c,t-d,t-e + 锚点 t-b = 5 个
        assertThat(result.nodes()).hasSize(5);
        assertThat(result.nodes()).extracting(GraphNodeView::id)
                .contains("t-a", "t-b", "t-c", "t-d", "t-e");

        // 边：a→b, b→c, c→d, b→e = 4 条（去重）
        assertThat(result.edges()).isNotEmpty();
        assertThat(result.edges().size()).isGreaterThanOrEqualTo(4);

        // 每条边的 from/to 都在节点集中
        var nodeIds = result.nodes().stream().map(GraphNodeView::id).toList();
        for (FlowEdgeView e : result.edges()) {
            assertThat(nodeIds).contains(e.from(), e.to());
        }
    }

    @Test
    void neighborhood_shouldNotContainCrossProjectData() {
        // 查询 tenant2 的数据，确保不返回 tenant1 的节点
        LineageGraph result = querySvc.neighborhood(T2, P2, "t-x", 5,
                GraphNodeView.Granularity.TABLE, null, null, null, null);

        // 只有 tenant2 的 t-x 锚点（无邻居）
        assertThat(result.nodes()).extracting(GraphNodeView::id)
                .doesNotContain("t-a", "t-b", "t-c", "t-d", "t-e");
    }

    @Test
    void neighborhood_withLayerFilter_filtersNodes() {
        // 过滤 DWD 层：t-b (DWD) + 锚点 t-b → 上游 t-a (ODS，排除) + 下游 t-c(DWS,排除)...
        LineageGraph result = querySvc.neighborhood(T1, P1, "t-b", 5,
                GraphNodeView.Granularity.TABLE, List.of("DWD"), null, null, null);

        // 只有 DWD 层节点（t-b 本身 + t-e(dwd_order_detail)）
        assertThat(result.nodes()).extracting(GraphNodeView::layer)
                .allMatch(layer -> layer == null || "DWD".equals(layer));
    }

    // ═════════════════════════════════════════════════════════════
    // T014: attrs 富化
    // ═════════════════════════════════════════════════════════════

    @Test
    void tableAttrs_shouldContainProducersAndSyncedRows() {
        // t-d (ads_orders_report) 由 task12 (etl_dws_to_ads) 产出，有今日 SYNCED
        LineageGraph result = querySvc.downstream(T1, P1, "t-c", 5,
                GraphNodeView.Granularity.TABLE, null, null, null, null);

        var t4Node = result.nodes().stream()
                .filter(n -> "t-d".equals(n.id()))
                .findFirst().orElse(null);
        assertThat(t4Node).isNotNull();

        Map<String, Object> attrs = t4Node.attrs();
        assertThat(attrs).isNotNull();

        // producers: task12 writes to t-d
        @SuppressWarnings("unchecked")
        List<String> producers = (List<String>) attrs.get("producers");
        assertThat(producers).isNotNull();
        assertThat(producers).contains("etl_dws_to_ads");

        // syncedRowsToday: runToday SYNCED t4 with 1204882
        Object srt = attrs.get("syncedRowsToday");
        assertThat(srt).isNotNull();
        long synced = srt instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(srt));
        assertThat(synced).isGreaterThan(0);

        // lastSyncDate should be present
        assertThat(attrs.get("lastSyncDate")).isNotNull();
    }

    // ═════════════════════════════════════════════════════════════
    // T016: 过滤生效
    // ═════════════════════════════════════════════════════════════

    @Test
    void traverse_withConfidenceFilter_filtersEdges() {
        // t-b→t-c (CONFIRMED) and t-c→t-d (UNVERIFIED)
        // Filter only CONFIRMED: edges should only include CONFIRMED ones
        LineageGraph result = querySvc.downstream(T1, P1, "t-b", 5,
                GraphNodeView.Granularity.TABLE, null, null,
                List.of("CONFIRMED"), null);

        // All returned edges should have CONFIRMED confidence
        for (FlowEdgeView e : result.edges()) {
            assertThat(e.confidence()).isIn(FlowEdgeView.Confidence.CONFIRMED, null);
        }
        // Edge b→c is CONFIRMED, c→d is UNVERIFIED → c→d should be filtered out
        boolean hasUnverifiedEdge = result.edges().stream()
                .anyMatch(e -> "t-c".equals(e.from()) && "t-d".equals(e.to()));
        assertThat(hasUnverifiedEdge).isFalse();
    }

    // ═════════════════════════════════════════════════════════════
    // T027: search 中缀命中 + 跨项目零泄漏
    // ═════════════════════════════════════════════════════════════

    @Test
    void search_shouldFindBySubstring() {
        // "order_detail" 中缀命中 t-e (dwd_order_detail)
        List<SearchCandidate> results = querySvc.search(T1, P1, "order_detail", null, 0, 100);

        assertThat(results).isNotEmpty();
        assertThat(results).extracting(SearchCandidate::name)
                .contains("dwd_order_detail");
        assertThat(results).extracting(SearchCandidate::type)
                .contains("TABLE");
    }

    @Test
    void search_shouldRespectTypeFilter() {
        // 只搜 TABLE，不应返回 COLUMN 或 METRIC
        List<SearchCandidate> results = querySvc.search(T1, P1, "order", List.of("TABLE"), 0, 100);

        assertThat(results).allMatch(r -> "TABLE".equals(r.type()));
        assertThat(results).extracting(SearchCandidate::name)
                .contains("ods_orders");
    }

    @Test
    void search_shouldNotReturnCrossProjectData() {
        // 搜 "order_detail"：tenant1 有 dwd_order_detail，tenant2 有 dwd_order_detail_bak
        List<SearchCandidate> results = querySvc.search(T1, P1, "order_detail", null, 0, 100);

        // tenant1 结果不含 tenant2 的资产
        assertThat(results).extracting(SearchCandidate::name)
                .doesNotContain("dwd_order_detail_bak");
        // tenant2 搜索也不应返回 tenant1 的资产
        List<SearchCandidate> t2Results = querySvc.search(T2, P2, "order_detail", null, 0, 100);
        assertThat(t2Results).extracting(SearchCandidate::name)
                .doesNotContain("dwd_order_detail");
    }

    @Test
    void search_emptyKeyword_returnsEmpty() {
        List<SearchCandidate> results = querySvc.search(T1, P1, "", null, 0, 100);
        assertThat(results).isEmpty();
    }

    // ═════════════════════════════════════════════════════════════
    // T033: impact edges 闭合 + reachableTotal 与分页解耦
    // ═════════════════════════════════════════════════════════════

    @Test
    void impact_shouldReturnEdgesAndReachableTotal() {
        // t-a 下游：t-b→t-c→t-d→t-e (4 个下游表)
        ImpactResult result = querySvc.impact(T1, P1, "t-a", 10, 0, 100,
                null, null, null, null);

        // edges 非空
        assertThat(result.edges()).isNotEmpty();

        // edges 闭合于 downstream 集（含 root）
        var nodeIds = new java.util.HashSet<String>();
        nodeIds.add(result.root().id());
        result.downstream().forEach(n -> nodeIds.add(n.id()));
        for (FlowEdgeView e : result.edges()) {
            assertThat(nodeIds).contains(e.from(), e.to());
        }

        // reachableTotal >= nodeCount（当前页）
        assertThat(result.reachableTotal()).isGreaterThanOrEqualTo(result.nodeCount());

        // downstream 包含所有 4 个表
        assertThat(result.downstream()).extracting(GraphNodeView::id)
                .contains("t-b", "t-c", "t-d", "t-e");
    }

    @Test
    void impact_paginationIndependentOfReachableTotal() {
        // 分页 limit=2：当前页只有 2 个节点，但 reachableTotal 应该是 4
        ImpactResult result = querySvc.impact(T1, P1, "t-a", 10, 0, 2,
                null, null, null, null);

        assertThat(result.downstream()).hasSizeLessThanOrEqualTo(2);
        // 真实可达总数 = 4（与分页解耦）
        assertThat(result.reachableTotal()).isEqualTo(4);
        assertThat(result.totalIsLowerBound()).isFalse();
    }

    // ═════════════════════════════════════════════════════════════
    // T033: paths 多路径去重 + 无路径
    // ═════════════════════════════════════════════════════════════

    @Test
    void pathsBetween_existingPath_returnsNodesAndEdges() {
        // a→b 有直连路径
        LineagePath result = querySvc.pathsBetween(T1, P1, "t-a", "t-b", 10);

        assertThat(result.pathExists()).isTrue();
        assertThat(result.nodes()).isNotEmpty();
        assertThat(result.edges()).isNotEmpty();

        // 节点包含端点
        assertThat(result.nodes()).extracting(GraphNodeView::id)
                .contains("t-a", "t-b");

        // 边连接 from→to
        assertThat(result.edges().stream().anyMatch(e ->
                "t-a".equals(e.from()) && "t-b".equals(e.to()))).isTrue();
    }

    @Test
    void pathsBetween_noPath_returnsEmptyWithPathExistsFalse() {
        // a→d 有路径 (a→b→c→d)，但查反向 d→a 无 FLOWS_TO 路径
        LineagePath result = querySvc.pathsBetween(T1, P1, "t-d", "t-a", 10);

        assertThat(result.pathExists()).isFalse();
        assertThat(result.nodes()).isEmpty();
        assertThat(result.edges()).isEmpty();
    }

    @Test
    void pathsBetween_multiPath_dedupsNodesAndEdges() {
        // a→e 有两条路径：a→b→e 和 a→b→c→d（不经过e）... Actually a→e via b→e
        // a→b→e directly
        LineagePath result = querySvc.pathsBetween(T1, P1, "t-a", "t-e", 10);

        assertThat(result.pathExists()).isTrue();
        // 节点去重
        long distinctNodeCount = result.nodes().stream().map(GraphNodeView::id).distinct().count();
        assertThat(result.nodes().size()).isEqualTo(distinctNodeCount);

        // 边去重
        long distinctEdgeCount = result.edges().stream()
                .map(e -> e.from() + "|" + e.to()).distinct().count();
        assertThat(result.edges().size()).isEqualTo(distinctEdgeCount);
    }

    // ═════════════════════════════════════════════════════════════
    // T037: 列级 traverse 边字段齐全
    // ═════════════════════════════════════════════════════════════

    @Test
    void columnDownstream_shouldReturnEdgesWithAllFields() {
        // col-a3(amount) → col-b2(clean_date) 是 DIRECT
        LineageGraph result = querySvc.columnDownstream(T1, P1, "col-a3", 5, null, null);

        assertThat(result.nodes()).isNotEmpty();
        assertThat(result.edges()).isNotEmpty();

        // 边应有 transform/confidence/source 字段
        FlowEdgeView edge = result.edges().get(0);
        assertThat(edge.granularity()).isEqualTo(GraphNodeView.Granularity.COLUMN);
        assertThat(edge.transform()).isNotNull();
        assertThat(edge.confidence()).isNotNull();
        assertThat(edge.source()).isNotNull();
    }

    // ═════════════════════════════════════════════════════════════
    // T038: 表→列展开（列清单 + 列级派生边 + 邻接列闭合）
    // ═════════════════════════════════════════════════════════════

    @Test
    void expandColumns_shouldReturnTableColumnsWithParentAndDerivesEdges() {
        // t-a(ods_orders) 有 col-a1/col-a2/col-a3；col-a3-[:DERIVES_FROM]->col-b2(t-b)
        LineageGraph result = querySvc.expandColumns(T1, P1, "t-a");

        // 本表 3 列均在，parentId 指回本表
        var ownCols = result.nodes().stream()
                .filter(n -> "t-a".equals(n.parentId())).toList();
        assertThat(ownCols).extracting(GraphNodeView::id)
                .containsExactlyInAnyOrder("col-a1", "col-a2", "col-a3");
        assertThat(ownCols).allSatisfy(n ->
                assertThat(n.type()).isEqualTo(GraphNodeView.NodeType.COLUMN));

        // 邻接列 col-b2 带入（parentId=其所属表 t-b），使列到列边可闭合渲染
        var nbCol = result.nodes().stream()
                .filter(n -> "col-b2".equals(n.id())).findFirst().orElse(null);
        assertThat(nbCol).isNotNull();
        assertThat(nbCol.parentId()).isEqualTo("t-b");

        // 列级派生边：col-a3 → col-b2，两端都在节点集内（闭合）
        var nodeIds = result.nodes().stream().map(GraphNodeView::id).toList();
        assertThat(result.edges()).isNotEmpty();
        assertThat(result.edges()).anySatisfy(e -> {
            assertThat(e.granularity()).isEqualTo(GraphNodeView.Granularity.COLUMN);
            assertThat(nodeIds).contains(e.from(), e.to());
        });
        assertThat(result.edges()).extracting(e -> e.from() + "→" + e.to())
                .contains("col-a3→col-b2");
    }

    @Test
    void expandColumns_shouldNotLeakAcrossProjects() {
        // tenant2 的表 t-y(dwd_order_detail_bak) 只有 col-y1；不得返回 tenant1 的列
        LineageGraph result = querySvc.expandColumns(T2, P2, "t-y");
        assertThat(result.nodes()).extracting(GraphNodeView::id)
                .contains("col-y1")
                .doesNotContain("col-a1", "col-a2", "col-a3", "col-b2");
    }

    @Test
    void tableAttrs_shouldExposeColumnCountForExpandAffordance() {
        // 展开 chevron 依赖 attrs.columnCount>0；t-a 有 3 列
        LineageGraph result = querySvc.neighborhood(T1, P1, "t-a", 1,
                GraphNodeView.Granularity.TABLE, null, null, null, null);
        // 锚点 t-a 走 fallback 补回，也须带 columnCount=3（否则焦点表 chevron 不显示）
        var tA = result.nodes().stream()
                .filter(n -> "t-a".equals(n.id())).findFirst().orElse(null);
        assertThat(tA).isNotNull();
        assertThat(tA.attrs()).isNotNull();
        assertThat(((Number) tA.attrs().get("columnCount")).intValue()).isEqualTo(3);
        // 邻居 t-b（遍历 end）columnCount=2
        var tB = result.nodes().stream()
                .filter(n -> "t-b".equals(n.id())).findFirst().orElse(null);
        assertThat(tB).isNotNull();
        assertThat(tB.attrs()).isNotNull();
        assertThat(((Number) tB.attrs().get("columnCount")).intValue()).isEqualTo(2);
    }

    // ═════════════════════════════════════════════════════════════
    // Helper: load seed Cypher inline (fallback when file: not available)
    // ═════════════════════════════════════════════════════════════

    private static void loadSeedInline(Driver driver) {
        String seed = """
                    CREATE (ds1:Datasource {id: 'ds-1', tenantId: 1, projectId: 1, name: 'ods_db'});
                    CREATE (t1:Table {id: 't-a', tenantId: 1, projectId: 1, qualifiedName: 'ods_orders', layer: 'ODS', datasourceId: 'ds-1'});
                    CREATE (t2:Table {id: 't-b', tenantId: 1, projectId: 1, qualifiedName: 'dwd_orders_clean', layer: 'DWD', datasourceId: 'ds-1'});
                    CREATE (t3:Table {id: 't-c', tenantId: 1, projectId: 1, qualifiedName: 'dws_orders_agg', layer: 'DWS', datasourceId: 'ds-1'});
                    CREATE (t4:Table {id: 't-d', tenantId: 1, projectId: 1, qualifiedName: 'ads_orders_report', layer: 'ADS', datasourceId: 'ds-1'});
                    CREATE (tE:Table {id: 't-e', tenantId: 1, projectId: 1, qualifiedName: 'dwd_order_detail', layer: 'DWD', datasourceId: 'ds-1'});
                    CREATE (t1)-[:HAS_DATASOURCE]->(ds1);
                    CREATE (t2)-[:HAS_DATASOURCE]->(ds1);
                    CREATE (t3)-[:HAS_DATASOURCE]->(ds1);
                    CREATE (t4)-[:HAS_DATASOURCE]->(ds1);
                    CREATE (tE)-[:HAS_DATASOURCE]->(ds1);
                    CREATE (t1)-[:FLOWS_TO {taskDefId: 10, confidence: 'CONFIRMED', source: 'SQL_PARSED'}]->(t2);
                    CREATE (t2)-[:FLOWS_TO {taskDefId: 11, confidence: 'CONFIRMED', source: 'SQL_PARSED'}]->(t3);
                    CREATE (t3)-[:FLOWS_TO {taskDefId: 12, confidence: 'UNVERIFIED', source: 'FORM'}]->(t4);
                    CREATE (t2)-[:FLOWS_TO {taskDefId: 13, confidence: 'CONFIRMED', source: 'SQL_PARSED'}]->(tE);
                    CREATE (c1a:Column {id: 'col-a1', tenantId: 1, projectId: 1, name: 'order_id', dataType: 'BIGINT', ordinal: 1});
                    CREATE (c1b:Column {id: 'col-a2', tenantId: 1, projectId: 1, name: 'order_date', dataType: 'DATE', ordinal: 2});
                    CREATE (c1c:Column {id: 'col-a3', tenantId: 1, projectId: 1, name: 'amount', dataType: 'DECIMAL', ordinal: 3});
                    CREATE (t1)-[:HAS_COLUMN]->(c1a);
                    CREATE (t1)-[:HAS_COLUMN]->(c1b);
                    CREATE (t1)-[:HAS_COLUMN]->(c1c);
                    CREATE (c2a:Column {id: 'col-b1', tenantId: 1, projectId: 1, name: 'order_id', dataType: 'BIGINT', ordinal: 1});
                    CREATE (c2b:Column {id: 'col-b2', tenantId: 1, projectId: 1, name: 'clean_date', dataType: 'DATE', ordinal: 2});
                    CREATE (t2)-[:HAS_COLUMN]->(c2a);
                    CREATE (t2)-[:HAS_COLUMN]->(c2b);
                    CREATE (c3a:Column {id: 'col-c1', tenantId: 1, projectId: 1, name: 'order_id', dataType: 'BIGINT', ordinal: 1});
                    CREATE (c3b:Column {id: 'col-c2', tenantId: 1, projectId: 1, name: 'total_amount', dataType: 'DECIMAL', ordinal: 2});
                    CREATE (t3)-[:HAS_COLUMN]->(c3a);
                    CREATE (t3)-[:HAS_COLUMN]->(c3b);
                    CREATE (cEa:Column {id: 'col-e1', tenantId: 1, projectId: 1, name: 'detail_id', dataType: 'BIGINT', ordinal: 1});
                    CREATE (tE)-[:HAS_COLUMN]->(cEa);
                    CREATE (c1c)-[:DERIVES_FROM {taskDefId: 11, transform: 'DIRECT', confidence: 'CONFIRMED', source: 'SQL_PARSED'}]->(c2b);
                    CREATE (c2b)-[:DERIVES_FROM {taskDefId: 12, transform: 'AGGREGATE', confidence: 'UNVERIFIED', source: 'FORM'}]->(c3b);
                    CREATE (task10:Task {id: 'task-10', tenantId: 1, projectId: 1, name: 'etl_ods_to_dwd', taskDefId: 10});
                    CREATE (task11:Task {id: 'task-11', tenantId: 1, projectId: 1, name: 'etl_dwd_to_dws', taskDefId: 11});
                    CREATE (task12:Task {id: 'task-12', tenantId: 1, projectId: 1, name: 'etl_dws_to_ads', taskDefId: 12});
                    CREATE (task13:Task {id: 'task-13', tenantId: 1, projectId: 1, name: 'etl_dwd_detail', taskDefId: 13});
                    CREATE (task10)-[:WRITES]->(t2);
                    CREATE (task11)-[:WRITES]->(t3);
                    CREATE (task12)-[:WRITES]->(t4);
                    CREATE (task13)-[:WRITES]->(tE);
                    CREATE (task10)-[:READS]->(t1);
                    CREATE (task11)-[:READS]->(t2);
                    CREATE (task12)-[:READS]->(t3);
                    CREATE (task13)-[:READS]->(t2);
                    CREATE (metric1:Metric {id: 'metric-1', tenantId: 1, projectId: 1, name: 'order_count', metricType: 'DERIVED'});
                    CREATE (metric1)-[:COMPUTED_FROM]->(t1);
                    CREATE (run1:TaskRun {id: 'run-1', tenantId: 1, projectId: 1, bizDate: date('2026-06-30'), instanceId: 'inst-1'});
                    CREATE (run1)-[:SYNCED {rowCount: 15000}]->(t1);
                    CREATE (run1)-[:SYNCED {rowCount: 14800}]->(t2);
                    CREATE (runToday:TaskRun {id: 'run-today', tenantId: 1, projectId: 1, bizDate: date(), instanceId: 'inst-today'});
                    CREATE (runToday)-[:SYNCED {rowCount: 1204882}]->(t4);
                    CREATE (runToday)-[:SYNCED {rowCount: 5000}]->(tE);
                    CREATE (ds2:Datasource {id: 'ds-2', tenantId: 2, projectId: 2, name: 'warehouse_db'});
                    CREATE (t5:Table {id: 't-x', tenantId: 2, projectId: 2, qualifiedName: 'dw_customers', layer: 'DWD', datasourceId: 'ds-2'});
                    CREATE (t5)-[:HAS_DATASOURCE]->(ds2);
                    CREATE (t6:Table {id: 't-y', tenantId: 2, projectId: 2, qualifiedName: 'dwd_order_detail_bak', layer: 'DWD', datasourceId: 'ds-2'});
                    CREATE (t6)-[:HAS_DATASOURCE]->(ds2);
                    CREATE (c5a:Column {id: 'col-x1', tenantId: 2, projectId: 2, name: 'cust_id', dataType: 'BIGINT', ordinal: 1});
                    CREATE (t5)-[:HAS_COLUMN]->(c5a);
                    CREATE (c6a:Column {id: 'col-y1', tenantId: 2, projectId: 2, name: 'detail_id', dataType: 'BIGINT', ordinal: 1});
                    CREATE (t6)-[:HAS_COLUMN]->(c6a);
                    CREATE (metric2:Metric {id: 'metric-2', tenantId: 2, projectId: 2, name: 'customer_ltv', metricType: 'DERIVED'});
                    CREATE (metric2)-[:COMPUTED_FROM]->(t5);
                    CREATE (task20:Task {id: 'task-20', tenantId: 2, projectId: 2, name: 'etl_tenant2', taskDefId: 20});
                    CREATE (task20)-[:WRITES]->(t5);
                    CREATE (task20)-[:WRITES]->(t6);
                    """;
        // 整份种子是「共享变量作用域」的单条多-CREATE 查询：t1/t2/… 需跨 CREATE 保持绑定。
        // 分号会被 driver 视为「一次多语句」而拒绝；但按分号逐条拆分又会丢失变量绑定
        // （relationship CREATE 会生成全新空节点而非连接既有节点）。故去掉分号、整体单条执行。
        try (Session session = driver.session()) {
            session.run(seed.replace(";", "")).consume();
        }
    }
}
