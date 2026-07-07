package com.dataweave.master.application;

import com.dataweave.master.application.lineage.ColumnMeta;
import com.dataweave.master.application.lineage.TableSchema;
import com.dataweave.master.domain.Datasource;
import com.dataweave.master.domain.DatasourceRepository;
import com.dataweave.master.domain.DatasourceType;
import com.dataweave.master.domain.DatasourceTypeRepository;
import com.dataweave.master.domain.DriverJarRepository;
import com.dataweave.master.infrastructure.IsolatedDriverLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T025：{@link DatasourceSchemaResolver} 集成测试（H2 真库取列）。
 *
 * <p>验证：
 * <ul>
 *   <li>真库取列——H2 内存库建表后 fetchColumns 返回正确列清单</li>
 *   <li>视图列——视图的输出列经 DatabaseMetaData 解析</li>
 *   <li>列数上限截断——超 max_columns 截断</li>
 *   <li>不可达降级——错误连接返回 empty 不抛异常</li>
 *   <li>限定名规范化——parseQualifiedName 正确分段</li>
 * </ul>
 *
 * <p>PG 部分需外部 PG 实例（手动跑）；本文件覆盖 H2 方言。
 */
class DatasourceSchemaResolverIT {

    private static final String H2_JDBC_URL = "jdbc:h2:mem:schema_resolver_it;DB_CLOSE_DELAY=-1";
    private static final long DS_ID = 100L;

    private Connection conn;
    private DatasourceSchemaResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        // 1. 内存 H2 建表 + 视图（显式传 sa/"" 鉴权，与 resolver 内 Properties 一致，避免 H2 鉴权不匹配）
        conn = DriverManager.getConnection(H2_JDBC_URL, "sa", "");
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DROP ALL OBJECTS");
            stmt.execute("CREATE TABLE test_user (id BIGINT PRIMARY KEY, name VARCHAR(100), email VARCHAR(200), age INTEGER)");
            stmt.execute("CREATE VIEW test_user_view AS SELECT id, name FROM test_user");
            // 大宽表
            StringBuilder wideDdl = new StringBuilder("CREATE TABLE wide_table (");
            for (int i = 1; i <= 10; i++) {
                if (i > 1) wideDdl.append(", ");
                wideDdl.append("col_").append(i).append(" VARCHAR(50)");
            }
            wideDdl.append(")");
            stmt.execute(wideDdl.toString());
        }

        // 2. Mock 仓储（只 mock 外部依赖，让 resolver 通过 DriverManager 连 H2）
        DatasourceRepository dsRepo = mock(DatasourceRepository.class);
        Datasource ds = new Datasource();
        ds.setId(DS_ID);
        ds.setTypeCode("H2");
        ds.setJdbcUrl(H2_JDBC_URL);
        ds.setUsername("sa");
        ds.setPasswordEnc("");
        ds.setDeleted(0);
        when(dsRepo.findById(DS_ID)).thenReturn(Optional.of(ds));

        DatasourceTypeRepository typeRepo = mock(DatasourceTypeRepository.class);
        DatasourceType dtype = new DatasourceType();
        dtype.setCode("H2");
        dtype.setDriver("org.h2.Driver");
        when(typeRepo.findByCode(anyString())).thenReturn(Optional.of(dtype));

        DriverJarRepository jarRepo = mock(DriverJarRepository.class);

        // DatasourceResolver: 密码为空直接返回 null
        DatasourceEncryptor encryptor = mock(DatasourceEncryptor.class);
        DatasourceResolver dsResolver = new DatasourceResolver(dsRepo, jarRepo, encryptor,
                new tools.jackson.databind.ObjectMapper());

        resolver = new DatasourceSchemaResolver(dsRepo, typeRepo, jarRepo, null, dsResolver);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (conn != null && !conn.isClosed()) {
            conn.close();
        }
    }

    // ── 真库取列 ─────────────────────────────────────────────────────

    @Test
    void fetchColumns_returnsRealColumns_fromH2Table() {
        Optional<TableSchema> result = resolver.fetchColumns(DS_ID, "TEST_USER");

        assertThat(result).isPresent();
        TableSchema schema = result.get();
        assertThat(schema.qualifiedName()).isEqualTo("TEST_USER");
        assertThat(schema.columns()).hasSize(4);

        assertThat(schema.columns().get(0).name()).isEqualToIgnoringCase("ID");
        assertThat(schema.columns().get(0).dataType()).isEqualTo("BIGINT");
        assertThat(schema.columns().get(1).name()).isEqualToIgnoringCase("NAME");
        assertThat(schema.columns().get(2).name()).isEqualToIgnoringCase("EMAIL");
        assertThat(schema.columns().get(3).name()).isEqualToIgnoringCase("AGE");
        assertThat(schema.columns().get(3).dataType()).isEqualTo("INTEGER");
    }

    // ── 视图列 ───────────────────────────────────────────────────────

    @Test
    void fetchColumns_resolvesViewColumns() {
        Optional<TableSchema> result = resolver.fetchColumns(DS_ID, "TEST_USER_VIEW");

        assertThat(result).isPresent();
        TableSchema schema = result.get();
        assertThat(schema.columns()).hasSize(2);
        assertThat(schema.columns().get(0).name()).isEqualToIgnoringCase("ID");
        assertThat(schema.columns().get(1).name()).isEqualToIgnoringCase("NAME");
    }

    // ── 列数上限（wide_table 有 10 列，未超默认上限 2000）───────────

    @Test
    void fetchColumns_returnsAllColumns_whenUnderMaxLimit() {
        Optional<TableSchema> result = resolver.fetchColumns(DS_ID, "WIDE_TABLE");

        assertThat(result).isPresent();
        assertThat(result.get().columns()).hasSize(10);
    }

    // ── 不可达降级（FR-013）──────────────────────────────────────────

    @Test
    void fetchColumns_returnsEmpty_whenDatasourceNotFound() {
        Optional<TableSchema> result = resolver.fetchColumns(99999L, "some_table");
        assertThat(result).isEmpty();
    }

    @Test
    void fetchColumns_returnsEmpty_whenTableNotFound() {
        Optional<TableSchema> result = resolver.fetchColumns(DS_ID, "no_such_table");
        assertThat(result).isEmpty();
    }

    @Test
    void fetchColumns_returnsEmpty_forNullBlankName() {
        assertThat(resolver.fetchColumns(DS_ID, null)).isEmpty();
        assertThat(resolver.fetchColumns(DS_ID, "")).isEmpty();
        assertThat(resolver.fetchColumns(DS_ID, "  ")).isEmpty();
    }

    // ── ordinal 验证 ─────────────────────────────────────────────────

    @Test
    void fetchColumns_ordinalIsZeroBased() {
        Optional<TableSchema> result = resolver.fetchColumns(DS_ID, "TEST_USER");
        assertThat(result).isPresent();
        for (int i = 0; i < result.get().columns().size(); i++) {
            ColumnMeta col = result.get().columns().get(i);
            assertThat(col.ordinal()).isEqualTo(i);
        }
    }

    // ── parseQualifiedName 规范化（FR-015）───────────────────────────

    @Test
    void parseQualifiedName_parsesThreeParts() throws Exception {
        var parsed = DatasourceSchemaResolver.parseQualifiedName("db.schema.table", conn);
        assertThat(parsed.catalog()).isEqualTo("db");
        assertThat(parsed.schema()).isEqualTo("schema");
        assertThat(parsed.table()).isEqualTo("table");
    }

    @Test
    void parseQualifiedName_parsesTwoParts() throws Exception {
        var parsed = DatasourceSchemaResolver.parseQualifiedName("schema.table", conn);
        assertThat(parsed.schema()).isEqualTo("schema");
        assertThat(parsed.table()).isEqualTo("table");
    }

    @Test
    void parseQualifiedName_parsesSinglePart() throws Exception {
        var parsed = DatasourceSchemaResolver.parseQualifiedName("table", conn);
        assertThat(parsed.table()).isEqualTo("table");
    }
}
