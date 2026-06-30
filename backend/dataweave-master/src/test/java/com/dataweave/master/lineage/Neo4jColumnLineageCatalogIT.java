package com.dataweave.master.lineage;

import com.dataweave.master.application.lineage.ColumnLineageCatalog;
import com.dataweave.master.application.lineage.TableSchema;
import com.dataweave.master.infrastructure.lineage.Neo4jColumnLineageCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 024 T014：列 catalog 的 neo4j 读侧 IT。
 *
 * <p>seed {@code :Table-[:HAS_COLUMN]->:Column} 后，
 * {@link Neo4jColumnLineageCatalog#lookupTable} 能读回有序 {@link TableSchema}。
 * 也覆盖：空结果（表不存在/无列）、租户/项目隔离。
 */
class Neo4jColumnLineageCatalogIT extends Neo4jTestSupport {

    private Driver driver;
    private ColumnLineageCatalog catalog;

    @BeforeEach
    void setUp() {
        driver = newDriver();
        cleanDb(driver);
        catalog = new Neo4jColumnLineageCatalog(driver);
    }

    /** 直接 Cypher seed 一个 Table + 有序列，避开 ensureColumn 的 private 访问限制。 */
    private void seedColumn(String qualifiedName, String colName, String dataType, int ordinal,
                            long tenantId, long projectId) {
        try (Session s = driver.session()) {
            s.run("""
                    MERGE (t:Table {tenantId:$tid, projectId:$pid, qualifiedName:$qn})
                    WITH t
                    MERGE (t)-[:HAS_COLUMN]->(c:Column {name:$cn, tenantId:$tid, projectId:$pid})
                    ON CREATE SET c.dataType=$dt, c.ordinal=$ord
                    SET c.dataType=$dt, c.ordinal=$ord
                    """,
                    Map.of("tid", tenantId, "pid", projectId,
                            "qn", qualifiedName, "cn", colName,
                            "dt", dataType, "ord", ordinal)).consume();
        }
    }

    @Test
    void lookupTable_returnsOrderedColumns_whenSeeded() {
        seedColumn("orders_clean", "order_id", "BIGINT", 0, 1L, 1L);
        seedColumn("orders_clean", "total", "DECIMAL", 1, 1L, 1L);
        seedColumn("orders_clean", "created_at", "TIMESTAMP", 2, 1L, 1L);

        Optional<TableSchema> result = catalog.lookupTable(1L, 1L, "orders_clean");
        assertThat(result).isPresent();
        TableSchema schema = result.get();
        assertThat(schema.qualifiedName()).isEqualTo("orders_clean");
        assertThat(schema.columns()).hasSize(3);

        // 按 ordinal 有序
        assertThat(schema.columns().get(0)).satisfies(c -> {
            assertThat(c.name()).isEqualTo("order_id");
            assertThat(c.dataType()).isEqualTo("BIGINT");
            assertThat(c.ordinal()).isEqualTo(0);
        });
        assertThat(schema.columns().get(1)).satisfies(c -> {
            assertThat(c.name()).isEqualTo("total");
            assertThat(c.dataType()).isEqualTo("DECIMAL");
            assertThat(c.ordinal()).isEqualTo(1);
        });
        assertThat(schema.columns().get(2)).satisfies(c -> {
            assertThat(c.name()).isEqualTo("created_at");
            assertThat(c.dataType()).isEqualTo("TIMESTAMP");
            assertThat(c.ordinal()).isEqualTo(2);
        });
    }

    @Test
    void lookupTable_returnsEmpty_whenNoColumns() {
        // 表存在但无列 → empty
        try (Session s = driver.session()) {
            s.run("MERGE (t:Table {tenantId:1, projectId:1, qualifiedName:'empty_table'})").consume();
        }

        Optional<TableSchema> result = catalog.lookupTable(1L, 1L, "empty_table");
        assertThat(result).isEmpty();
    }

    @Test
    void lookupTable_returnsEmpty_whenTableMissing() {
        Optional<TableSchema> result = catalog.lookupTable(1L, 1L, "no_such_table");
        assertThat(result).isEmpty();
    }

    @Test
    void lookupTable_respectsTenantIsolation() {
        seedColumn("shared_table", "col_a", "TEXT", 0, 1L, 1L);

        // tenant 2 查不到 tenant 1 的表
        Optional<TableSchema> result = catalog.lookupTable(2L, 1L, "shared_table");
        assertThat(result).isEmpty();

        // tenant 1 查得到
        assertThat(catalog.lookupTable(1L, 1L, "shared_table")).isPresent();
    }

    @Test
    void lookupTable_respectsProjectIsolation() {
        seedColumn("proj_table", "col_a", "TEXT", 0, 1L, 1L);

        // project 2 查不到 project 1 的表
        Optional<TableSchema> result = catalog.lookupTable(1L, 2L, "proj_table");
        assertThat(result).isEmpty();

        // project 1 查得到
        assertThat(catalog.lookupTable(1L, 1L, "proj_table")).isPresent();
    }

    @Test
    void lookupTable_returnsEmpty_forNullBlankName() {
        assertThat(catalog.lookupTable(1L, 1L, null)).isEmpty();
        assertThat(catalog.lookupTable(1L, 1L, "")).isEmpty();
        assertThat(catalog.lookupTable(1L, 1L, "  ")).isEmpty();
    }
}
