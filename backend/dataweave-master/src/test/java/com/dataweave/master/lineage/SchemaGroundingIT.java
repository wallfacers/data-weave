package com.dataweave.master.lineage;

import com.dataweave.master.application.DatasourceSchemaResolver;
import com.dataweave.master.application.lineage.CatalogFixtures;
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
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * T031：Schema 接地集成测试（neo4j 直连）。
 *
 * <p>验证：
 * <ul>
 *   <li>DatasourceBoundCatalog 经实时抓取解析 schema → 回填 neo4j 列（含 dataType/ordinal）</li>
 *   <li>回填后 neo4j catalog 可查回填列——证实 schema 接地闭环</li>
 *   <li>缓存命中后不重复连库（越用越省，FR-017/SC-006）</li>
 *   <li>越界列/不存在表 → 返回 empty 不抛</li>
 * </ul>
 */
class SchemaGroundingIT extends Neo4jTestSupport {

    private Driver driver;
    private Neo4jLineageStore store;
    private Neo4jColumnLineageCatalog neo4jCatalog;
    private Neo4jColumnBackfillWriter backfillWriter;
    private DatasourceRepository datasourceRepository;

    private AtomicInteger fetchCalls;
    private DatasourceSchemaResolver schemaResolver;

    @BeforeEach
    void setUp() {
        DatasourceBoundCatalog.clearCache();
        driver = GraphDatabase.driver(boltUri, AuthTokens.basic("neo4j", neo4jPassword));
        cleanDb(driver);
        store = newStore();
        neo4jCatalog = new Neo4jColumnLineageCatalog(driver);
        backfillWriter = new Neo4jColumnBackfillWriter(driver);

        // mock DatasourceRepository
        datasourceRepository = mock(DatasourceRepository.class);
        Datasource ds = new Datasource();
        ds.setId(10L);
        ds.setHost("localhost");
        ds.setPort(5432);
        ds.setDatabaseName("testdb");
        ds.setName("test-ds");
        ds.setTypeCode("POSTGRES");
        ds.setDeleted(0);
        doAnswer(inv -> Optional.of(ds)).when(datasourceRepository).findById(anyLong());

        // 计数实时抓取调用次数
        fetchCalls = new AtomicInteger(0);
        schemaResolver = new DatasourceSchemaResolver(null, null, null, null, null) {
            @Override
            public Optional<TableSchema> fetchColumns(long datasourceId, String qualifiedName) {
                fetchCalls.incrementAndGet();
                return switch (qualifiedName.toLowerCase()) {
                    case "public.orders" -> Optional.of(new TableSchema("public.orders", List.of(
                            new ColumnMeta("order_id", "BIGINT", 0),
                            new ColumnMeta("customer", "VARCHAR", 1),
                            new ColumnMeta("amount", "DECIMAL", 2))));
                    case "public.users" -> Optional.of(new TableSchema("public.users", List.of(
                            new ColumnMeta("user_id", "BIGINT", 0),
                            new ColumnMeta("name", "VARCHAR", 1),
                            new ColumnMeta("email", "VARCHAR", 2))));
                    default -> Optional.empty();
                };
            }
        };
    }

    @AfterEach
    void tearDown() {
        if (driver != null) driver.close();
        DatasourceBoundCatalog.clearCache();
    }

    // ── Schema 解析 + neo4j 回填 ─────────────────────────────────────

    @Test
    void resolvesSchemaAndBackfillsToNeo4j() {
        var catalog = new DatasourceBoundCatalog(10L, neo4jCatalog, schemaResolver, backfillWriter, datasourceRepository);

        // 首次：实时抓取 → 回填 neo4j
        Optional<TableSchema> result = catalog.lookupTable(1L, 1L, "public.orders");
        assertThat(result).isPresent();
        assertThat(result.get().columns()).hasSize(3);
        assertThat(result.get().columns().get(0).name()).isEqualTo("order_id");
        assertThat(result.get().columns().get(0).dataType()).isEqualTo("BIGINT");
        assertThat(fetchCalls.get()).isEqualTo(1);

        // 验证回填：neo4j catalog 可查回填列
        Optional<TableSchema> neo4jResult = neo4jCatalog.lookupTable(1L, 1L, "public.orders");
        assertThat(neo4jResult).isPresent();
        assertThat(neo4jResult.get().columns()).hasSize(3);
        // dataType 已回填（非 null）
        assertThat(neo4jResult.get().columns().get(0).dataType()).isEqualTo("BIGINT");
        assertThat(neo4jResult.get().columns().get(1).dataType()).isEqualTo("VARCHAR");
        // ordinal 已回填
        assertThat(neo4jResult.get().columns().get(0).ordinal()).isEqualTo(0);
        assertThat(neo4jResult.get().columns().get(2).ordinal()).isEqualTo(2);
    }

    // ── 缓存命中，不重复连库（SC-006） ─────────────────────────────

    @Test
    void cacheHitAvoidsRepeatFetch() {
        var catalog = new DatasourceBoundCatalog(10L, neo4jCatalog, schemaResolver, backfillWriter, datasourceRepository);

        // 首次解析
        catalog.lookupTable(1L, 1L, "public.users");
        assertThat(fetchCalls.get()).isEqualTo(1);

        // 二次：命中进程缓存
        catalog.lookupTable(1L, 1L, "public.users");
        assertThat(fetchCalls.get()).isEqualTo(1);
    }

    // ── neo4j 层命中 → 回填进程缓存 → 不连库 ─────────────────────

    @Test
    void neo4jHitBackfillsProcessCache() {
        var catalog = new DatasourceBoundCatalog(10L, neo4jCatalog, schemaResolver, backfillWriter, datasourceRepository);

        // 首次通过实时抓取 + 回填 neo4j
        catalog.lookupTable(1L, 1L, "public.orders");
        assertThat(fetchCalls.get()).isEqualTo(1);

        // 清进程缓存（模拟另一实例或过期）
        DatasourceBoundCatalog.clearCache();

        // 二次：neo4j 层命中 → 回填进程缓存（不连库）
        catalog.lookupTable(1L, 1L, "public.orders");
        assertThat(fetchCalls.get()).isEqualTo(1); // 未新增连库
    }

    // ── 不存在表 → empty ──────────────────────────────────────────

    @Test
    void unknownTableReturnsEmpty() {
        var catalog = new DatasourceBoundCatalog(10L, neo4jCatalog, schemaResolver, backfillWriter, datasourceRepository);

        Optional<TableSchema> result = catalog.lookupTable(1L, 1L, "no_such_table");
        assertThat(result).isEmpty();
    }

    // ── 跨实例：不同 DatasourceBoundCatalog 实例共享缓存 ──────────

    @Test
    void cacheSharedAcrossCatalogInstances() {
        var catalog1 = new DatasourceBoundCatalog(10L, neo4jCatalog, schemaResolver, backfillWriter, datasourceRepository);
        catalog1.lookupTable(1L, 1L, "public.users");
        assertThat(fetchCalls.get()).isEqualTo(1);

        // 新建另一个实例（同 datasourceId）
        var catalog2 = new DatasourceBoundCatalog(10L, neo4jCatalog, schemaResolver, backfillWriter, datasourceRepository);
        catalog2.lookupTable(1L, 1L, "public.users");
        assertThat(fetchCalls.get()).isEqualTo(1); // 命中静态缓存
    }
}
