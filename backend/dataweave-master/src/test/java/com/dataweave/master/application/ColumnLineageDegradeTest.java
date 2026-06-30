package com.dataweave.master.application;

import com.dataweave.master.application.lineage.ColumnEdge;
import com.dataweave.master.application.lineage.ColumnLineageCatalog;
import com.dataweave.master.application.lineage.ColumnLineageResult;
import com.dataweave.master.application.lineage.Confidence;
import com.dataweave.master.application.lineage.Transform;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.dataweave.master.application.lineage.CatalogFixtures.catalog;
import static com.dataweave.master.application.lineage.CatalogFixtures.table;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 列级 SQL 血缘解析 —— US3：解析不了时干净降级，绝不抛异常（契约 C1/C2）。
 */
class ColumnLineageDegradeTest {

    private final SqlColumnLineageExtractor extractor =
            new SqlColumnLineageExtractor(new SqlTableExtractor());

    // ---- T021 catalog 缺源表 → AST 启发式 UNVERIFIED，不抛 ----

    @Test
    void missingSourceTableDegradesToUnverified() {
        ColumnLineageCatalog cat = catalog(table("dwd", "id")); // 故意不提供 unknown_tbl

        ColumnLineageResult r = extractor.extract(
                "INSERT INTO dwd (id) SELECT id FROM unknown_tbl", cat, 0L, 0L);

        assertThat(r.degraded()).isTrue();
        assertThat(r.edges())
                .extracting(e -> e.srcTable().qualifiedName(), ColumnEdge::srcCol,
                        ColumnEdge::dstCol, ColumnEdge::confidence)
                .containsExactly(tuple("unknown_tbl", "id", "id", Confidence.UNVERIFIED));
    }

    @Test
    void missingSourceTableWithAliasResolvesViaAlias() {
        ColumnLineageCatalog cat = catalog(table("dwd", "oid", "uname"));

        ColumnLineageResult r = extractor.extract(
                "INSERT INTO dwd (oid, uname) SELECT o.id, o.name FROM unknown_tbl o", cat, 0L, 0L);

        assertThat(r.degraded()).isTrue();
        assertThat(r.edges())
                .allMatch(e -> e.confidence() == Confidence.UNVERIFIED)
                .extracting(e -> e.srcTable().qualifiedName(), ColumnEdge::srcCol, ColumnEdge::dstCol)
                .containsExactlyInAnyOrder(
                        tuple("unknown_tbl", "id", "oid"),
                        tuple("unknown_tbl", "name", "uname"));
    }

    // ---- T022 DDL / 不可解析 → parsed=false，退表级 ----

    @Test
    void ddlAndGarbageReturnUnparsed() {
        ColumnLineageCatalog cat = catalog(table("dwd", "id"));

        for (String sql : List.of(
                "CREATE TABLE dwd (id INT)",
                "this is not sql at all",
                "INSERT INTO dwd (id) VALUES (${dynamic_var})")) {
            ColumnLineageResult r = extractor.extract(sql, cat, 0L, 0L);
            assertThat(r.parsed()).as("sql=%s", sql).isFalse();
            assertThat(r.edges()).as("sql=%s", sql).isEmpty();
        }
    }

    // ---- T023 常量列 getColumnOrigins 空 → 该列无边、其余正常、degraded ----

    @Test
    void constantColumnYieldsNoEdgeButOthersNormal() {
        ColumnLineageCatalog cat = catalog(
                table("ods_order", "id", "user_id", "amount"),
                table("dwd", "id", "flag"));

        ColumnLineageResult r = extractor.extract(
                "INSERT INTO dwd (id, flag) SELECT id, 1 FROM ods_order", cat, 0L, 0L);

        assertThat(r.parsed()).isTrue();
        assertThat(r.degraded()).isTrue(); // 常量 flag 无源列 → 标降级
        assertThat(r.edges())
                .extracting(ColumnEdge::dstCol, ColumnEdge::srcCol, ColumnEdge::transform)
                .containsExactly(tuple("id", "id", Transform.DIRECT));
    }

    // 窗口函数列：Calcite 把源归到 ORDER BY 列，记为 EXPRESSION（非空 origin，不降级该列）
    @Test
    void windowFunctionColumnTracesToOrderingColumn() {
        ColumnLineageCatalog cat = catalog(
                table("ods_order", "id", "user_id", "amount"),
                table("dwd", "id", "rn"));

        ColumnLineageResult r = extractor.extract(
                "INSERT INTO dwd (id, rn) "
                        + "SELECT id, ROW_NUMBER() OVER (ORDER BY id) FROM ods_order", cat, 0L, 0L);

        assertThat(r.parsed()).isTrue();
        assertThat(r.edges())
                .extracting(ColumnEdge::dstCol, ColumnEdge::srcCol)
                .contains(tuple("id", "id"), tuple("rn", "id"));
    }

    // ---- T024 fuzz：任何输入都不抛（契约 C1） ----

    @Test
    void neverThrowsOnAnyInput() {
        ColumnLineageCatalog cat = catalog(table("dwd", "id"));
        String[] inputs = {
                null, "", "   ", ";", ";;;",
                "SELECT", "INSERT INTO", "INSERT INTO dwd",
                "SELECT * FROM", "(((", "));DROP TABLE x;--",
                "INSERT INTO dwd (id) SELECT id FROM a JOIN b JOIN c JOIN d",
                "WITH a AS (SELECT 1) SELECT * FROM a",
                "INSERT INTO dwd (id) SELECT 1",
                "MERGE INTO dwd USING src ON dwd.id=src.id WHEN MATCHED THEN UPDATE SET id=src.id",
                "SELECT 中文列 FROM 中文表",
                "INSERT INTO dwd (id) SELECT id FROM t WHERE x = ${var} AND y = {{ph}}",
        };
        for (String sql : inputs) {
            assertThatCode(() -> {
                ColumnLineageResult r1 = extractor.extract(sql, cat, 0L, 0L);
                ColumnLineageResult r2 = extractor.extract(sql, null, 0L, 0L);
                assertThat(r1).isNotNull();
                assertThat(r2).isNotNull();
                assertThat(r1.edges()).isNotNull();
            }).as("sql=%s", sql).doesNotThrowAnyException();
        }
    }
}
