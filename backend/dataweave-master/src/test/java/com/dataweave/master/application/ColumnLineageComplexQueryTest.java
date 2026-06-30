package com.dataweave.master.application;

import com.dataweave.master.application.lineage.ColumnEdge;
import com.dataweave.master.application.lineage.ColumnLineageCatalog;
import com.dataweave.master.application.lineage.ColumnLineageResult;
import com.dataweave.master.application.lineage.Transform;
import org.junit.jupiter.api.Test;

import static com.dataweave.master.application.lineage.CatalogFixtures.catalog;
import static com.dataweave.master.application.lineage.CatalogFixtures.table;
import static com.dataweave.master.application.lineage.CatalogFixtures.typed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 列级 SQL 血缘解析 —— US2：穿透 JOIN / CTE / UNION / 子查询 / 聚合。
 */
class ColumnLineageComplexQueryTest {

    private final SqlColumnLineageExtractor extractor =
            new SqlColumnLineageExtractor(new SqlTableExtractor());

    // ---- T014 JOIN 多表列溯源 ----

    @Test
    void joinTracesEachColumnToItsPhysicalTable() {
        ColumnLineageCatalog cat = catalog(
                table("ods_order", "id", "user_id", "amount"),
                table("ods_user", "id", "name"),
                table("dwd", "oid", "uname"));

        ColumnLineageResult r = extractor.extract(
                "INSERT INTO dwd (oid, uname) "
                        + "SELECT o.id, u.name FROM ods_order o "
                        + "JOIN ods_user u ON o.user_id = u.id", cat);

        assertThat(r.parsed()).isTrue();
        assertThat(r.edges())
                .extracting(e -> e.srcTable().qualifiedName(), ColumnEdge::srcCol, ColumnEdge::dstCol)
                .containsExactlyInAnyOrder(
                        tuple("ods_order", "id", "oid"),
                        tuple("ods_user", "name", "uname"));
    }

    // ---- T015 CTE 穿透 + 子查询 ----

    @Test
    void cteIsTransparentToPhysicalSource() {
        ColumnLineageCatalog cat = catalog(
                table("ods_order", "id", "user_id", "amount"),
                table("dwd", "id"));

        ColumnLineageResult r = extractor.extract(
                "INSERT INTO dwd (id) "
                        + "WITH t AS (SELECT id FROM ods_order) SELECT id FROM t", cat);

        assertThat(r.parsed()).isTrue();
        assertThat(r.edges())
                .extracting(e -> e.srcTable().qualifiedName(), ColumnEdge::srcCol, ColumnEdge::dstCol)
                .containsExactly(tuple("ods_order", "id", "id"));
    }

    @Test
    void derivedSubqueryIsTransparent() {
        ColumnLineageCatalog cat = catalog(
                table("ods_order", "id", "user_id", "amount"),
                table("dwd", "id"));

        ColumnLineageResult r = extractor.extract(
                "INSERT INTO dwd (id) SELECT id FROM (SELECT id FROM ods_order) x", cat);

        assertThat(r.parsed()).isTrue();
        assertThat(r.edges())
                .extracting(e -> e.srcTable().qualifiedName(), ColumnEdge::srcCol)
                .containsExactly(tuple("ods_order", "id"));
    }

    // ---- T016 UNION 列溯源为各分支并集 ----

    @Test
    void unionTracesToAllBranches() {
        ColumnLineageCatalog cat = catalog(
                table("ods_order", "id", "user_id", "amount"),
                table("ods_order2", "id", "user_id", "amount"),
                table("dwd", "id"));

        ColumnLineageResult r = extractor.extract(
                "INSERT INTO dwd (id) "
                        + "SELECT id FROM ods_order UNION ALL SELECT id FROM ods_order2", cat);

        assertThat(r.parsed()).isTrue();
        assertThat(r.edges())
                .extracting(e -> e.srcTable().qualifiedName(), ColumnEdge::srcCol, ColumnEdge::dstCol)
                .containsExactlyInAnyOrder(
                        tuple("ods_order", "id", "id"),
                        tuple("ods_order2", "id", "id"));
    }

    // ---- T017 聚合列 → AGGREGATE ----

    @Test
    void aggregateColumnIsClassifiedAggregate() {
        ColumnLineageCatalog cat = catalog(
                typed("ods_order", "id:INT", "user_id:INT", "amount:DECIMAL"),
                typed("dwd", "uid:INT", "total:DECIMAL"));

        ColumnLineageResult r = extractor.extract(
                "INSERT INTO dwd (uid, total) "
                        + "SELECT user_id, SUM(amount) FROM ods_order GROUP BY user_id", cat);

        assertThat(r.parsed()).isTrue();
        assertThat(r.edges())
                .extracting(ColumnEdge::dstCol, ColumnEdge::srcCol, ColumnEdge::transform)
                .containsExactlyInAnyOrder(
                        tuple("uid", "user_id", Transform.DIRECT),
                        tuple("total", "amount", Transform.AGGREGATE));
    }
}
