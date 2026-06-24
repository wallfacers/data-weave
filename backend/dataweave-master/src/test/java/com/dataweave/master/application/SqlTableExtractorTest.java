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
}
