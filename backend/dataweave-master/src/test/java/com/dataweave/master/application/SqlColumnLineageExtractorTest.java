package com.dataweave.master.application;

import com.dataweave.master.application.lineage.ColumnEdge;
import com.dataweave.master.application.lineage.ColumnLineageCatalog;
import com.dataweave.master.application.lineage.ColumnLineageResult;
import com.dataweave.master.application.lineage.Confidence;
import com.dataweave.master.application.lineage.Transform;
import org.junit.jupiter.api.Test;

import static com.dataweave.master.application.lineage.CatalogFixtures.catalog;
import static com.dataweave.master.application.lineage.CatalogFixtures.table;
import static com.dataweave.master.application.lineage.CatalogFixtures.typed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 列级 SQL 血缘解析 —— US1：直传 / 表达式 / {@code *} 展开。
 */
class SqlColumnLineageExtractorTest {

    private final SqlColumnLineageExtractor extractor =
            new SqlColumnLineageExtractor(new SqlTableExtractor());

    // ---- US1: T007 直引列 → DIRECT/CONFIRMED ----

    @Test
    void directColumnsAreDirectConfirmed() {
        ColumnLineageCatalog cat = catalog(
                table("ods_order", "id", "user_id", "amount"),
                table("dwd_order", "id", "uid", "amt"));

        ColumnLineageResult r = extractor.extract(
                "INSERT INTO dwd_order (id, uid, amt) "
                        + "SELECT id, user_id, amount FROM ods_order", cat);

        assertThat(r.parsed()).isTrue();
        assertThat(r.edges())
                .extracting(e -> e.srcTable().qualifiedName(), ColumnEdge::srcCol,
                        e -> e.dstTable().qualifiedName(), ColumnEdge::dstCol,
                        ColumnEdge::transform, ColumnEdge::confidence)
                .containsExactlyInAnyOrder(
                        tuple("ods_order", "id", "dwd_order", "id", Transform.DIRECT, Confidence.CONFIRMED),
                        tuple("ods_order", "user_id", "dwd_order", "uid", Transform.DIRECT, Confidence.CONFIRMED),
                        tuple("ods_order", "amount", "dwd_order", "amt", Transform.DIRECT, Confidence.CONFIRMED));
    }

    // ---- US1: T008 表达式列 → EXPRESSION ----

    @Test
    void scalarExpressionColumnIsExpression() {
        ColumnLineageCatalog cat = catalog(
                typed("ods_order", "id:INT", "user_id:INT", "amount:DECIMAL"),
                typed("dwd_order", "id:INT", "uid:INT", "amt:DECIMAL"));

        ColumnLineageResult r = extractor.extract(
                "INSERT INTO dwd_order (id, uid, amt) "
                        + "SELECT id, user_id, amount * 1.1 FROM ods_order", cat);

        assertThat(r.parsed()).isTrue();
        assertThat(edge(r, "amt").transform()).isEqualTo(Transform.EXPRESSION);
        assertThat(edge(r, "amt").srcCol()).isEqualTo("amount");
        assertThat(edge(r, "id").transform()).isEqualTo(Transform.DIRECT);
    }

    @Test
    void multiSourceExpressionMapsToBothSources() {
        ColumnLineageCatalog cat = catalog(
                typed("ods_order", "id:INT", "price:DECIMAL", "qty:INT"),
                typed("dwd_order", "id:INT", "total:DECIMAL"));

        ColumnLineageResult r = extractor.extract(
                "INSERT INTO dwd_order (id, total) "
                        + "SELECT id, price * qty FROM ods_order", cat);

        assertThat(r.parsed()).isTrue();
        assertThat(r.edges())
                .filteredOn(e -> e.dstCol().equals("total"))
                .extracting(ColumnEdge::srcCol, ColumnEdge::transform)
                .containsExactlyInAnyOrder(
                        tuple("price", Transform.EXPRESSION),
                        tuple("qty", Transform.EXPRESSION));
    }

    // ---- US1: T009 SELECT * 按列序展开 ----

    @Test
    void starExpandsToPerColumnDirect() {
        ColumnLineageCatalog cat = catalog(
                table("ods_order", "id", "uid", "amt"),
                table("dwd_order", "id", "uid", "amt"));

        ColumnLineageResult r = extractor.extract(
                "INSERT INTO dwd_order (id, uid, amt) SELECT * FROM ods_order", cat);

        assertThat(r.parsed()).isTrue();
        assertThat(r.edges())
                .extracting(ColumnEdge::srcCol, ColumnEdge::dstCol, ColumnEdge::transform)
                .containsExactlyInAnyOrder(
                        tuple("id", "id", Transform.DIRECT),
                        tuple("uid", "uid", Transform.DIRECT),
                        tuple("amt", "amt", Transform.DIRECT));
    }

    // ---- 工具 ----

    private static ColumnEdge edge(ColumnLineageResult r, String dstCol) {
        return r.edges().stream()
                .filter(e -> e.dstCol().equals(dstCol))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no edge for dst col: " + dstCol));
    }
}
