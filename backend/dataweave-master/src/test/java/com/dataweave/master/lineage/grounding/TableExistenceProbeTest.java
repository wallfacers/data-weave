package com.dataweave.master.lineage.grounding;

import com.dataweave.master.application.DatasourceResolver;
import com.dataweave.master.application.DatasourceSchemaResolver;
import com.dataweave.master.application.lineage.grounding.TableExistence;
import com.dataweave.master.domain.Datasource;
import com.dataweave.master.domain.DatasourceRepository;
import com.dataweave.master.domain.DatasourceTypeRepository;
import com.dataweave.master.domain.DriverJarRepository;
import com.dataweave.master.infrastructure.IsolatedDriverLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T006：{@link DatasourceSchemaResolver#probeTable} 三态探针真 H2 单测。
 *
 * <p>连上+有表 → PRESENT；连上+无表 → ABSENT；连不上 → UNKNOWN。
 * 关键：ABSENT 与 UNKNOWN 必须区分（SC-003 误杀率 0 的地基）。
 */
class TableExistenceProbeTest {

    private static final String H2_URL =
            "jdbc:h2:mem:probe055;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE";

    @BeforeAll
    static void seedH2() throws Exception {
        try (Connection c = DriverManager.getConnection(H2_URL, "sa", "");
             Statement st = c.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS dw");
            st.execute("CREATE TABLE IF NOT EXISTS dw.orders (id INT, name VARCHAR(64))");
        }
    }

    private DatasourceSchemaResolver resolverFor(String jdbcUrl) {
        Datasource ds = mock(Datasource.class);
        when(ds.getDeleted()).thenReturn(0);
        when(ds.getTypeCode()).thenReturn("H2");
        when(ds.getJdbcUrl()).thenReturn(jdbcUrl);
        when(ds.getUsername()).thenReturn("sa");
        when(ds.getDriverJarId()).thenReturn(null);

        DatasourceRepository dsRepo = mock(DatasourceRepository.class);
        when(dsRepo.findById(1L)).thenReturn(Optional.of(ds));

        DatasourceResolver dsResolver = mock(DatasourceResolver.class);
        when(dsResolver.decryptPassword(any())).thenReturn("");

        DatasourceTypeRepository typeRepo = mock(DatasourceTypeRepository.class);
        when(typeRepo.findByCode(anyString())).thenReturn(Optional.empty()); // → 内置 org.h2.Driver 兜底
        DriverJarRepository driverRepo = mock(DriverJarRepository.class);
        IsolatedDriverLoader isolatedLoader = mock(IsolatedDriverLoader.class);

        return new DatasourceSchemaResolver(dsRepo, typeRepo, driverRepo, isolatedLoader, dsResolver);
    }

    @Test
    void existing_table_is_present() {
        assertThat(resolverFor(H2_URL).probeTable(1L, "dw.orders")).isEqualTo(TableExistence.PRESENT);
    }

    @Test
    void missing_table_is_absent_not_unknown() {
        // 连库成功、表确不存在 → ABSENT（绝不能误判 UNKNOWN，否则接地层会保留幻觉表）
        assertThat(resolverFor(H2_URL).probeTable(1L, "dw.tmp_stage")).isEqualTo(TableExistence.ABSENT);
    }

    @Test
    void unreachable_datasource_is_unknown_not_absent() {
        // 坏 URL 连不上 → UNKNOWN（绝不能误判 ABSENT，否则会误杀真表）
        String badUrl = "jdbc:h2:tcp://127.0.0.1:1/nonexistent;CONNECT_TIMEOUT=1";
        assertThat(resolverFor(badUrl).probeTable(1L, "dw.orders")).isEqualTo(TableExistence.UNKNOWN);
    }

    @Test
    void unknown_datasource_id_is_unknown() {
        assertThat(resolverFor(H2_URL).probeTable(999L, "dw.orders")).isEqualTo(TableExistence.UNKNOWN);
    }
}
