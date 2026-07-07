package com.dataweave.master.lineage;

import com.dataweave.master.application.DatasourceSchemaResolver;
import com.dataweave.master.application.SqlColumnLineageExtractor;
import com.dataweave.master.application.SqlTableExtractor;
import com.dataweave.master.application.lineage.CatalogFixtures;
import com.dataweave.master.application.lineage.ColumnEdge;
import com.dataweave.master.application.lineage.ColumnLineageCatalog;
import com.dataweave.master.application.lineage.ColumnLineageResult;
import com.dataweave.master.application.lineage.ColumnMeta;
import com.dataweave.master.application.lineage.DatasourceBoundCatalog;
import com.dataweave.master.application.lineage.TableSchema;
import com.dataweave.master.domain.Datasource;
import com.dataweave.master.domain.DatasourceRepository;
import com.dataweave.master.domain.lineage.DatasourceCoord;
import com.dataweave.master.infrastructure.lineage.Neo4jColumnBackfillWriter;
import com.dataweave.master.infrastructure.lineage.Neo4jColumnLineageCatalog;
import com.dataweave.master.infrastructure.lineage.Neo4jLineageStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * T026：SELECT * 展开 + neo4j 回填集成测试（neo4j 真连）。
 *
 * <p>验证：
 * <ul>
 *   <li>Calcite + 简单 catalog 可正确展开 SELECT * → 逐列字段血缘</li>
 *   <li>{@link DatasourceBoundCatalog} 组合链正确解析并回填 neo4j 列</li>
 *   <li>neo4j 回填后 {@link Neo4jColumnLineageCatalog} 可查回填列</li>
 *   <li>未绑定数据源 → 退化纯 neo4j</li>
 * </ul>
 */
class SelectStarExpansionIT extends Neo4jTestSupport {

    private Driver driver;
    private Neo4jLineageStore store;
    private Neo4jColumnLineageCatalog neo4jCatalog;
    private Neo4jColumnBackfillWriter backfillWriter;
    private SqlColumnLineageExtractor extractor;

    @BeforeEach
    void setUp() {
        driver = newDriver();
        cleanDb(driver);
        store = new Neo4jLineageStore(driver);
        neo4jCatalog = new Neo4jColumnLineageCatalog(driver);
        backfillWriter = new Neo4jColumnBackfillWriter(driver);
        extractor = new SqlColumnLineageExtractor(new SqlTableExtractor());
    }

    @AfterEach
    void tearDown() {
        DatasourceBoundCatalog.clearCache();
    }

    // ── Calcite + 简单 catalog → SELECT * 展开 ───────────────────────

    @Test
    void selectStar_withSimpleCatalog_producesPerColumnEdges() {
        // SqlTableExtractor 要求小写标识符（Calcite UNCHANGED casing 限制）
        ColumnLineageCatalog catalog = CatalogFixtures.catalog(
                CatalogFixtures.typed("usr", "id:BIGINT", "name:VARCHAR", "email:VARCHAR", "age:INTEGER"),
                CatalogFixtures.typed("dw_snap", "id:BIGINT", "name:VARCHAR", "email:VARCHAR", "age:INTEGER"));

        String sql = "INSERT INTO dw_snap SELECT * FROM usr";
        ColumnLineageResult result = extractor.extractAndCrossCheck(sql, catalog, List.of(), 1L, 1L);

        assertThat(result.parsed()).isTrue();
        assertThat(result.edges()).hasSize(4);

        List<String> srcCols = result.edges().stream().map(ColumnEdge::srcCol).sorted().toList();
        assertThat(srcCols).containsExactly("age", "email", "id", "name");
    }

    @Test
    void selectStar_withSchemaQualifiedCatalog_producesPerColumnEdges() {
        // 验证 schema-qualified 表名
        ColumnLineageCatalog catalog = CatalogFixtures.catalog(
                CatalogFixtures.typed("public.usr", "id:BIGINT", "name:VARCHAR", "email:VARCHAR", "age:INTEGER"),
                CatalogFixtures.typed("public.dw_snap", "id:BIGINT", "name:VARCHAR", "email:VARCHAR", "age:INTEGER"));

        String sql = "INSERT INTO public.dw_snap SELECT * FROM public.usr";
        ColumnLineageResult result = extractor.extractAndCrossCheck(sql, catalog, List.of(), 1L, 1L);

        assertThat(result.parsed()).isTrue();
        assertThat(result.edges()).hasSize(4);
    }

    // ── DatasourceBoundCatalog 组合链 + neo4j 回填 ────────────────────

    @Test
    void boundCatalog_chain_backfillsColumnsToNeo4j() {
        DatasourceBoundCatalog catalog = createBoundCatalog(1L);

        // 首次 lookup：neo4j 空 → 走实时抓取 → 回填 neo4j + 缓存
        Optional<TableSchema> result = catalog.lookupTable(1L, 1L, "usr");
        assertThat(result).isPresent();
        assertThat(result.get().columns()).hasSize(4);

        // 清进程缓存 → 二次 lookup 走 neo4j
        DatasourceBoundCatalog.clearCache();
        Optional<TableSchema> neo4jResult = neo4jCatalog.lookupTable(1L, 1L, "usr");
        assertThat(neo4jResult).isPresent();
        assertThat(neo4jResult.get().columns()).hasSize(4);

        // 验证回填列包含 dataType/ordinal
        for (int i = 0; i < neo4jResult.get().columns().size(); i++) {
            ColumnMeta col = neo4jResult.get().columns().get(i);
            assertThat(col.dataType()).isNotNull();
            assertThat(col.ordinal()).isEqualTo(i);
        }
    }

    @Test
    void boundCatalog_unboundDatasource_skipsLiveFetch() {
        DatasourceBoundCatalog catalog = createBoundCatalog(null);

        // neo4j 无列 → 全 miss，返回 empty
        Optional<TableSchema> result = catalog.lookupTable(1L, 1L, "usr");
        assertThat(result).isEmpty();
    }

    // ── Calcite + DatasourceBoundCatalog → SELECT * 展开 ─────────────

    @Test
    void selectStar_withBoundCatalog_producesColumnEdges() {
        DatasourceBoundCatalog catalog = createBoundCatalog(1L);

        String sql = "INSERT INTO dw_snap SELECT * FROM usr";
        ColumnLineageResult result = extractor.extractAndCrossCheck(sql, catalog, List.of(), 1L, 1L);

        assertThat(result.parsed()).isTrue();
        assertThat(result.edges()).hasSize(4);

        // 所有边都是 usr → dw_snap
        for (ColumnEdge edge : result.edges()) {
            String srcTable = edge.srcTable().qualifiedName().toLowerCase();
            String dstTable = edge.dstTable().qualifiedName().toLowerCase();
            assertThat(srcTable).contains("usr");
            assertThat(dstTable).contains("snap");
        }
    }

    // ── 未绑定数据源 → SELECT * 降级 ─────────────────────────────────

    @Test
    void selectStar_unboundDatasource_degraded() {
        DatasourceBoundCatalog catalog = createBoundCatalog(null);
        String sql = "INSERT INTO dw_snap SELECT * FROM usr";

        ColumnLineageResult result = extractor.extractAndCrossCheck(sql, catalog, List.of(), 1L, 1L);
        // 无列元数据 → 列级解析降级
        assertThat(result.degraded()).isTrue();
    }

    // ── 辅助方法 ──────────────────────────────────────────────────────

    private DatasourceBoundCatalog createBoundCatalog(Long datasourceId) {
        DatasourceSchemaResolver resolver = new DatasourceSchemaResolver(null, null, null, null, null) {
            @Override
            public Optional<TableSchema> fetchColumns(long dsId, String qualifiedName) {
                String norm = qualifiedName.toLowerCase();
                // SqlTableExtractor 用小写返回表名，匹配小写
                if (norm.equals("usr") || norm.equals("public.usr") || (norm.contains("usr") && !norm.contains("snap"))) {
                    return Optional.of(CatalogFixtures.typed(
                            "usr", "id:BIGINT", "name:VARCHAR", "email:VARCHAR", "age:INTEGER"));
                }
                if (norm.equals("dw_snap") || norm.equals("public.dw_snap") || (norm.contains("snap") && !norm.contains("usr"))) {
                    return Optional.of(CatalogFixtures.typed(
                            "dw_snap", "id:BIGINT", "name:VARCHAR", "email:VARCHAR", "age:INTEGER"));
                }
                return Optional.empty();
            }
        };

        DatasourceRepository dsRepo = mock(DatasourceRepository.class);
        Datasource ds = new Datasource();
        ds.setId(datasourceId != null ? datasourceId : 1L);
        ds.setHost("localhost");
        ds.setPort(5432);
        ds.setDatabaseName("testdb");
        ds.setName("test-ds");
        ds.setTypeCode("POSTGRES");
        ds.setDeleted(0);
        doAnswer(inv -> Optional.of(ds)).when(dsRepo).findById(anyLong());

        return new DatasourceBoundCatalog(datasourceId, neo4jCatalog, resolver, backfillWriter, dsRepo);
    }
}
