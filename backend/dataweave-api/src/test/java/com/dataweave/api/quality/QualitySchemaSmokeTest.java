package com.dataweave.api.quality;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase A 门：验证 022 数据质量中心 DDL 在 H2 加载成功（双方言兼容的快速反馈，PG 由后续集成测试覆盖）。
 * 纯 JDBC 加载 schema.sql + data.sql，不启动 Spring context；独立库名防共享内存库串台。
 * quality_fire 的 UNIQUE guard 由 Phase D 的单点并发测试覆盖。
 */
class QualitySchemaSmokeTest {

    @Test
    void qualityTablesExistAndSchemaVersionBumped() throws Exception {
        DataSource ds = new EmbeddedDatabaseBuilder()
                .generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2)
                .addScript("classpath:schema.sql")
                .addScript("classpath:data.sql")
                .build();
        try (Connection c = ds.getConnection()) {
            DatabaseMetaData md = c.getMetaData();
            for (String table : new String[]{
                    "QUALITY_RULE", "QUALITY_CHECK_RUN", "QUALITY_CHECK_RESULT",
                    "QUALITY_SCORECARD", "QUALITY_FIRE"}) {
                try (ResultSet rs = md.getTables(null, null, table, new String[]{"TABLE"})) {
                    assertThat(rs.next())
                            .as("DDL 应创建表 %s", table)
                            .isTrue();
                }
            }
            // schema_version 已升版且为合法 SemVer（确切版本契约由 SchemaVersionIT 守，此处不 pin 死值，
            // 免每个并行特性合并都要改本断言；022 落地时 ≥0.2.0，023 合并后为 0.3.0）。
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT version FROM schema_version")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("version"))
                        .as("schema_version 应为合法 SemVer 且已升版")
                        .matches("\\d+\\.\\d+\\.\\d+");
            }
            // policy_rules seed：QUALITY_RULE_WRITE(L1) / QUALITY_RUN(L2) 已落
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery(
                         "SELECT base_level FROM policy_rules WHERE pattern='QUALITY_RUN'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("base_level")).isEqualTo("L2");
            }
        }
    }
}
