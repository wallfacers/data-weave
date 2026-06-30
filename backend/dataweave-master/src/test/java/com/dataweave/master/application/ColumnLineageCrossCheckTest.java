package com.dataweave.master.application;

import com.dataweave.master.application.lineage.ColumnEdge;
import com.dataweave.master.application.lineage.ColumnLineageCatalog;
import com.dataweave.master.application.lineage.ColumnLineageResult;
import com.dataweave.master.application.lineage.Confidence;
import com.dataweave.master.application.lineage.TableRef;
import com.dataweave.master.application.lineage.Transform;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.dataweave.master.application.lineage.CatalogFixtures.catalog;
import static com.dataweave.master.application.lineage.CatalogFixtures.table;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 列级 SQL 血缘 —— Phase 6：A×B 交叉校验（一致 CONFIRMED / 仅解析 CONFIRMED / 冲突 CONFLICT / 仅声明 DECLARED）。
 */
class ColumnLineageCrossCheckTest {

    private final SqlColumnLineageExtractor extractor =
            new SqlColumnLineageExtractor(new SqlTableExtractor());

    private static ColumnEdge declared(String srcTable, String srcCol, String dstTable, String dstCol) {
        return new ColumnEdge(TableRef.of(srcTable), srcCol, TableRef.of(dstTable), dstCol,
                Transform.DIRECT, Confidence.CONFIRMED);
    }

    // ---- T029 一致 → CONFIRMED ----

    @Test
    void declarationMatchingParseIsConfirmed() {
        ColumnLineageCatalog cat = catalog(table("ods_order", "id"), table("dwd", "id"));

        ColumnLineageResult r = extractor.extractAndCrossCheck(
                "INSERT INTO dwd (id) SELECT id FROM ods_order", cat,
                List.of(declared("ods_order", "id", "dwd", "id")), 0L, 0L);

        assertThat(r.edges())
                .extracting(ColumnEdge::dstCol, ColumnEdge::confidence)
                .containsExactly(tuple("id", Confidence.CONFIRMED));
    }

    // ---- T029 仅解析 → CONFIRMED ----

    @Test
    void parseOnlyIsConfirmed() {
        ColumnLineageCatalog cat = catalog(table("ods_order", "id"), table("dwd", "id"));

        ColumnLineageResult r = extractor.extractAndCrossCheck(
                "INSERT INTO dwd (id) SELECT id FROM ods_order", cat, List.of(), 0L, 0L);

        assertThat(r.edges())
                .extracting(ColumnEdge::confidence)
                .containsExactly(Confidence.CONFIRMED);
    }

    // ---- T029 冲突 → CONFLICT（解析源 ≠ 声明源，两条都标 CONFLICT，不静默丢） ----

    @Test
    void conflictingSourceIsFlaggedConflict() {
        ColumnLineageCatalog cat = catalog(table("ods_order", "amount"), table("dwd", "amt"));

        ColumnLineageResult r = extractor.extractAndCrossCheck(
                "INSERT INTO dwd (amt) SELECT amount FROM ods_order", cat,
                List.of(declared("ods_other", "amount", "dwd", "amt")), 0L, 0L);

        assertThat(r.edges())
                .extracting(e -> e.srcTable().qualifiedName(), ColumnEdge::dstCol, ColumnEdge::confidence)
                .containsExactlyInAnyOrder(
                        tuple("ods_order", "amt", Confidence.CONFLICT),  // 解析边
                        tuple("ods_other", "amt", Confidence.CONFLICT)); // 声明边，未被静默丢弃
    }

    // ---- 仅声明（解析未触及该 dst）→ DECLARED（024 声明兜底） ----

    @Test
    void declarationOnlyForUntouchedColumnIsDeclared() {
        ColumnLineageCatalog cat = catalog(table("ods_order", "id"), table("dwd", "id", "tag"));

        ColumnLineageResult r = extractor.extractAndCrossCheck(
                "INSERT INTO dwd (id) SELECT id FROM ods_order", cat,
                List.of(declared("ext_dim", "tag", "dwd", "tag")), 0L, 0L);

        assertThat(r.edges())
                .filteredOn(e -> e.dstCol().equals("tag"))
                .extracting(e -> e.srcTable().qualifiedName(), ColumnEdge::confidence)
                .containsExactly(tuple("ext_dim", Confidence.DECLARED));
    }

    // ---- US3: SQL 完全无法解析（空结果）+ 声明兜底 → 全部 DECLARED ----

    @Test
    void parseCompletelyFailed_declaredEdgesAreDECLARED() {
        // 空 catalog + DDL 语句 → Calcite 完全解析不了 → edges=empty
        ColumnLineageCatalog cat = catalog();

        ColumnLineageResult r = extractor.extractAndCrossCheck(
                "ALTER TABLE orders_clean ADD COLUMN discount DECIMAL(10,2)", cat,
                List.of(
                        declared("ext_price", "discount", "orders_clean", "discount"),
                        declared("ext_tax", "tax", "orders_clean", "tax")),
                0L, 0L);

        // 解析失败但声明边全部以 DECLARED 写入（兜底建图可用性下限，US3 SC-003）
        assertThat(r.edges()).hasSize(2);
        assertThat(r.edges())
                .extracting(ColumnEdge::confidence)
                .containsOnly(Confidence.DECLARED);
    }

    // ---- US3: 无声明时解析失败 → 空结果（零回归） ----

    @Test
    void parseCompletelyFailed_noDeclaration_emptyResult() {
        ColumnLineageCatalog cat = catalog();

        ColumnLineageResult r = extractor.extractAndCrossCheck(
                "ALTER TABLE t ADD COLUMN x INT", cat, List.of(), 0L, 0L);

        assertThat(r.parsed()).isFalse();
        assertThat(r.edges()).isEmpty(); // 零回归：不声明则无列边
    }
}
