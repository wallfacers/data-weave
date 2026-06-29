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
 * 列级 SQL 血缘 —— Phase 6：A×B 交叉校验（一致 CONFIRMED / 仅解析 CONFIRMED / 冲突 CONFLICT）。
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
                List.of(declared("ods_order", "id", "dwd", "id")));

        assertThat(r.edges())
                .extracting(ColumnEdge::dstCol, ColumnEdge::confidence)
                .containsExactly(tuple("id", Confidence.CONFIRMED));
    }

    // ---- T029 仅解析 → CONFIRMED ----

    @Test
    void parseOnlyIsConfirmed() {
        ColumnLineageCatalog cat = catalog(table("ods_order", "id"), table("dwd", "id"));

        ColumnLineageResult r = extractor.extractAndCrossCheck(
                "INSERT INTO dwd (id) SELECT id FROM ods_order", cat, List.of());

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
                List.of(declared("ods_other", "amount", "dwd", "amt")));

        assertThat(r.edges())
                .extracting(e -> e.srcTable().qualifiedName(), ColumnEdge::dstCol, ColumnEdge::confidence)
                .containsExactlyInAnyOrder(
                        tuple("ods_order", "amt", Confidence.CONFLICT),  // 解析边
                        tuple("ods_other", "amt", Confidence.CONFLICT)); // 声明边，未被静默丢弃
    }

    // ---- 仅声明（解析未触及该 dst）→ CONFIRMED（Agent 源） ----

    @Test
    void declarationOnlyForUntouchedColumnIsConfirmed() {
        ColumnLineageCatalog cat = catalog(table("ods_order", "id"), table("dwd", "id", "tag"));

        ColumnLineageResult r = extractor.extractAndCrossCheck(
                "INSERT INTO dwd (id) SELECT id FROM ods_order", cat,
                List.of(declared("ext_dim", "tag", "dwd", "tag")));

        assertThat(r.edges())
                .filteredOn(e -> e.dstCol().equals("tag"))
                .extracting(e -> e.srcTable().qualifiedName(), ColumnEdge::confidence)
                .containsExactly(tuple("ext_dim", Confidence.CONFIRMED));
    }
}
