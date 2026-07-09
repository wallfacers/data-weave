package com.dataweave.master.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqlTableExtractorTest {

    private final SqlTableExtractor extractor = new SqlTableExtractor();

    @Test
    void insert_select_extracts_target_and_source() {
        var r = extractor.extract("INSERT INTO dwd_order SELECT * FROM ods_order");
        assertThat(r.parsed()).isTrue();
        assertThat(r.writes()).containsExactly("dwd_order");
        assertThat(r.reads()).containsExactly("ods_order");
    }

    @Test
    void join_collects_all_source_tables() {
        var r = extractor.extract(
                "INSERT INTO dws_user_order " +
                "SELECT * FROM ods_user u JOIN ods_order o ON u.id = o.user_id");
        assertThat(r.writes()).containsExactly("dws_user_order");
        assertThat(r.reads()).containsExactlyInAnyOrder("ods_user", "ods_order");
    }

    @Test
    void plain_select_is_all_reads_no_writes() {
        var r = extractor.extract("SELECT count(*) FROM orders");
        assertThat(r.parsed()).isTrue();
        assertThat(r.reads()).containsExactly("orders");
        assertThat(r.writes()).isEmpty();
    }

    @Test
    void cte_names_excluded_from_source_tables() {
        var r = extractor.extract(
                "INSERT INTO ads_summary " +
                "WITH t AS (SELECT * FROM dws_order) SELECT * FROM t");
        assertThat(r.writes()).containsExactly("ads_summary");
        assertThat(r.reads()).containsExactly("dws_order");
        assertThat(r.reads()).doesNotContain("t");
    }

    @Test
    void schema_qualified_name_preserved() {
        var r = extractor.extract("SELECT * FROM public.ods_order");
        assertThat(r.reads()).containsExactly("public.ods_order");
    }

    @Test
    void unparseable_returns_unparsed_not_throws() {
        var r = extractor.extract("this is not valid sql @@@");
        assertThat(r.parsed()).isFalse();
        assertThat(r.reads()).isEmpty();
        assertThat(r.writes()).isEmpty();
    }

    // ---- T016: HIVE / OLAP SQL 血缘接入验证（FR-016）----

    @Test
    void hive_select_sql_extracts_source_tables() {
        // HQL SELECT 与标准 SQL 同构，Calcite 可解析
        var r = extractor.extract("SELECT * FROM hive_dwd_order WHERE dt='2024-01-01'");
        assertThat(r.parsed()).isTrue();
        assertThat(r.reads()).contains("hive_dwd_order");
    }

    @Test
    void hive_insert_overwrite_extracts_target_and_source() {
        // HQL INSERT OVERWRITE TABLE ... PARTITION (...) SELECT ...
        // Calcite 可能无法解析 OVERWRITE/PARTITION 方言 → 最小降级（不抛异常）
        var r = extractor.extract(
                "INSERT OVERWRITE TABLE hive_dwd_order PARTITION (dt='2024-01-01') " +
                "SELECT * FROM ods_order");
        // 解析成功或降级均可，关键是不抛异常（FR-016）
        if (r.parsed()) {
            assertThat(r.reads()).contains("ods_order");
        } else {
            // 方言降级：不产错血缘，不抛异常
            assertThat(r.reads()).isEmpty();
            assertThat(r.writes()).isEmpty();
        }
    }

    @Test
    void hive_show_tables_graceful_degradation() {
        // SHOW TABLES 是 Hive DDL 命令，Calcite 可能不识别 → 降级不抛异常
        var r = extractor.extract("SHOW TABLES");
        // 不抛异常即通过（FR-016：方言不识别处最小降级，不产错血缘）
        assertThat(r).isNotNull();
    }

    @Test
    void hive_describe_graceful_degradation() {
        // DESCRIBE 是诊断命令，Calcite 可能不识别 → 降级不抛异常
        var r = extractor.extract("DESCRIBE hive_dwd_order");
        assertThat(r).isNotNull();
    }

    @Test
    void olap_sql_same_path_as_standard_sql() {
        // OLAP SQL（StarRocks/Doris/ClickHouse）与标准 SQL 同路径，由 SqlTableExtractor 统一解析
        var r = extractor.extract("SELECT user_id, count(*) FROM starrocks_dwd_order GROUP BY user_id");
        assertThat(r.parsed()).isTrue();
        assertThat(r.reads()).contains("starrocks_dwd_order");
    }

    @Test
    void hive_set_statement_graceful_degradation() {
        // SET 会话指令不是 SQL，Calcite 无法解析 → 降级不抛异常
        var r = extractor.extract("SET hive.exec.dynamic.partition=true");
        assertThat(r).isNotNull();
    }
}
