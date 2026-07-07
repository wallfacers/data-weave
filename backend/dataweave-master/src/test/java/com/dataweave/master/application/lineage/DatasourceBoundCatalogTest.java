package com.dataweave.master.application.lineage;

import com.dataweave.master.application.DatasourceSchemaResolver;
import com.dataweave.master.domain.Datasource;
import com.dataweave.master.domain.DatasourceRepository;
import com.dataweave.master.domain.lineage.DatasourceCoord;
import com.dataweave.master.infrastructure.lineage.Neo4jColumnBackfillWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * T027：{@link DatasourceBoundCatalog} 组合链单测。
 *
 * <p>验证四层解析顺序、缓存命中/回填、未绑定退化、全 miss 返回 empty。
 * 纯单测，不依赖 neo4j / DB。
 */
class DatasourceBoundCatalogTest {

    /** 用 CatalogFixtures 构造的 neo4j 层 fixture（步骤 2）。 */
    private ColumnLineageCatalog neo4jCatalog;

    /** 计数实时抓取调用次数，验证缓存命中后不重复连库。 */
    private AtomicInteger fetchCalls;
    private DatasourceSchemaResolver schemaResolver;
    private Neo4jColumnBackfillWriter backfillWriter;
    private DatasourceRepository datasourceRepository;

    @BeforeEach
    void setUp() {
        DatasourceBoundCatalog.clearCache();

        // 步骤 2：neo4j 层预置 "cached_table"
        neo4jCatalog = CatalogFixtures.catalog(
                CatalogFixtures.table("cached_table", "id", "name", "val"));

        fetchCalls = new AtomicInteger(0);
        schemaResolver = new DatasourceSchemaResolver(null, null, null, null, null) {
            @Override
            public Optional<TableSchema> fetchColumns(long datasourceId, String qualifiedName) {
                fetchCalls.incrementAndGet();
                if ("live_table".equals(qualifiedName)) {
                    return Optional.of(CatalogFixtures.table("live_table", "a", "b"));
                }
                return Optional.empty();
            }
        };

        // 不真正写 neo4j（单测无 neo4j）
        backfillWriter = new Neo4jColumnBackfillWriter(null) {
            @Override
            public void backfillColumns(DatasourceCoord coord, String qualifiedName,
                                        List<ColumnMeta> columns, long tenantId, long projectId) {
                // no-op for unit test
            }
        };

        // Mock 数据源仓库
        datasourceRepository = mock(DatasourceRepository.class);
        Datasource ds = new Datasource();
        ds.setId(1L);
        ds.setHost("localhost");
        ds.setPort(5432);
        ds.setDatabaseName("testdb");
        ds.setName("test-ds");
        ds.setTypeCode("POSTGRES");
        doAnswer(inv -> Optional.of(ds)).when(datasourceRepository).findById(anyLong());
    }

    @AfterEach
    void tearDown() {
        DatasourceBoundCatalog.clearCache();
    }

    // ── 步骤 1：进程 TTL 缓存命中 ─────────────────────────────────────

    @Test
    void cacheHit_returnsDirectly_withoutCallingNeo4j() {
        var catalog = new DatasourceBoundCatalog(1L, neo4jCatalog, schemaResolver, backfillWriter, datasourceRepository, 10_000);

        // 首次：走 neo4j → 命中了 cached_table → 回填缓存
        Optional<TableSchema> first = catalog.lookupTable(1L, 1L, "cached_table");
        assertThat(first).isPresent();
        assertThat(first.get().columns()).hasSize(3);

        // 二次：命中进程缓存
        Optional<TableSchema> second = catalog.lookupTable(1L, 1L, "cached_table");
        assertThat(second).isPresent();
        assertThat(second.get().columns()).hasSize(3);
    }

    // ── 步骤 2：neo4j 持久列目录命中 → 回填缓存 ─────────────────────

    @Test
    void neo4jHit_backfillsCache() {
        var catalog = new DatasourceBoundCatalog(1L, neo4jCatalog, schemaResolver, backfillWriter, datasourceRepository, 10_000);

        Optional<TableSchema> result = catalog.lookupTable(1L, 1L, "cached_table");
        assertThat(result).isPresent();
        assertThat(result.get().qualifiedName()).isEqualTo("cached_table");
        assertThat(fetchCalls.get()).isEqualTo(0);
    }

    // ── 步骤 3：数据源实时抓取（绑定了 datasourceId）───────────────

    @Test
    void liveFetch_whenNeo4jMisses_andDatasourceBound() {
        var catalog = new DatasourceBoundCatalog(1L, neo4jCatalog, schemaResolver, backfillWriter, datasourceRepository, 10_000);

        Optional<TableSchema> result = catalog.lookupTable(1L, 1L, "live_table");
        assertThat(result).isPresent();
        assertThat(result.get().qualifiedName()).isEqualTo("live_table");
        assertThat(result.get().columns()).hasSize(2);
        assertThat(fetchCalls.get()).isEqualTo(1);

        // 二次：命中进程缓存，不重复连库
        Optional<TableSchema> second = catalog.lookupTable(1L, 1L, "live_table");
        assertThat(second).isPresent();
        assertThat(fetchCalls.get()).isEqualTo(1);
    }

    // ── 步骤 4：全 miss → empty ─────────────────────────────────────

    @Test
    void allMiss_returnsEmpty() {
        var catalog = new DatasourceBoundCatalog(1L, neo4jCatalog, schemaResolver, backfillWriter, datasourceRepository, 10_000);

        Optional<TableSchema> result = catalog.lookupTable(1L, 1L, "no_such_table");
        assertThat(result).isEmpty();
        assertThat(fetchCalls.get()).isEqualTo(1);
    }

    // ── 未绑定数据源退化（FR-013 场景 6）────────────────────────────

    @Test
    void unboundDatasource_skipsLiveFetch_delegatesToNeo4jOnly() {
        var catalog = new DatasourceBoundCatalog(null, neo4jCatalog, schemaResolver, backfillWriter, datasourceRepository, 10_000);

        Optional<TableSchema> hit = catalog.lookupTable(1L, 1L, "cached_table");
        assertThat(hit).isPresent();

        Optional<TableSchema> miss = catalog.lookupTable(1L, 1L, "live_table");
        assertThat(miss).isEmpty();
        assertThat(fetchCalls.get()).isEqualTo(0);
    }

    // ── 新鲜度：evict + TTL 过期 ────────────────────────────────────

    @Test
    void evict_clearsCacheEntry() {
        var catalog = new DatasourceBoundCatalog(1L, neo4jCatalog, schemaResolver, backfillWriter, datasourceRepository, 10_000);

        catalog.lookupTable(1L, 1L, "live_table");
        assertThat(fetchCalls.get()).isEqualTo(1);

        catalog.evict("live_table");
        catalog.lookupTable(1L, 1L, "live_table");
        assertThat(fetchCalls.get()).isEqualTo(2);
    }

    @Test
    void evictAll_clearsAllEntriesForDatasource() {
        var catalog = new DatasourceBoundCatalog(1L, neo4jCatalog, schemaResolver, backfillWriter, datasourceRepository, 10_000);

        catalog.lookupTable(1L, 1L, "live_table");
        catalog.lookupTable(1L, 1L, "cached_table");
        assertThat(fetchCalls.get()).isEqualTo(1);

        catalog.evictAll();

        catalog.lookupTable(1L, 1L, "live_table");
        assertThat(fetchCalls.get()).isEqualTo(2);
    }

    @Test
    void ttlExpired_refetches() throws InterruptedException {
        var catalog = new DatasourceBoundCatalog(1L, neo4jCatalog, schemaResolver, backfillWriter, datasourceRepository, 1);

        catalog.lookupTable(1L, 1L, "live_table");
        assertThat(fetchCalls.get()).isEqualTo(1);

        Thread.sleep(5);

        catalog.lookupTable(1L, 1L, "live_table");
        assertThat(fetchCalls.get()).isEqualTo(2);
    }

    // ── 空/空白 qualifiedName ──────────────────────────────────────

    @Test
    void nullBlankQualifiedName_returnsEmpty() {
        var catalog = new DatasourceBoundCatalog(1L, neo4jCatalog, schemaResolver, backfillWriter, datasourceRepository, 10_000);

        assertThat(catalog.lookupTable(1L, 1L, null)).isEmpty();
        assertThat(catalog.lookupTable(1L, 1L, "")).isEmpty();
        assertThat(catalog.lookupTable(1L, 1L, "  ")).isEmpty();
        assertThat(fetchCalls.get()).isEqualTo(0);
    }

    // ── 异常降级：neo4j 层异常不阻断链路 ─────────────────────────

    @Test
    void neo4jException_delegatesToNextLayer() {
        ColumnLineageCatalog brokenNeo4j = (tid, pid, qn) -> {
            throw new RuntimeException("neo4j down");
        };
        var catalog = new DatasourceBoundCatalog(1L, brokenNeo4j, schemaResolver, backfillWriter, datasourceRepository, 10_000);

        Optional<TableSchema> result = catalog.lookupTable(1L, 1L, "live_table");
        assertThat(result).isPresent();
        assertThat(fetchCalls.get()).isEqualTo(1);
    }
}
