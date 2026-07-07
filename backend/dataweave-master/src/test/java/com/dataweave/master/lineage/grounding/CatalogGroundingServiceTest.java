package com.dataweave.master.lineage.grounding;

import com.dataweave.master.application.lineage.DatasourceBoundCatalog;
import com.dataweave.master.application.lineage.grounding.CatalogGroundingService;
import com.dataweave.master.application.lineage.grounding.GroundingDisposition;
import com.dataweave.master.application.lineage.grounding.SystemNamespaceClassifier;
import com.dataweave.master.application.lineage.grounding.TableExistence;
import com.dataweave.master.domain.lineage.ColumnEdge;
import com.dataweave.master.domain.lineage.Confidence;
import com.dataweave.master.domain.lineage.Direction;
import com.dataweave.master.domain.lineage.IoEdge;
import com.dataweave.master.domain.lineage.Source;
import com.dataweave.master.domain.lineage.TableRef;
import com.dataweave.master.domain.lineage.Transform;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T010：{@link CatalogGroundingService} 逐边接地算法单测（纯逻辑，mock 目录）。
 *
 * <p>验证来源分类（推断类可剔 / 确定性不可剔）、三态处置、系统排除、列级边连带剔除、处置留痕。
 */
class CatalogGroundingServiceTest {

    private final SystemNamespaceClassifier classifier = new SystemNamespaceClassifier("");
    private final CatalogGroundingService svc = new CatalogGroundingService(classifier);

    private static TableRef ref(String qn) {
        return new TableRef(null, qn, null);
    }

    private static IoEdge io(String qn, Source src, Confidence c) {
        return new IoEdge(ref(qn), Direction.READS, src, c);
    }

    @Test
    void ground_classifies_tri_state_and_system_by_source_class() {
        DatasourceBoundCatalog cat = mock(DatasourceBoundCatalog.class);
        when(cat.probeExistence(anyLong(), anyLong(), eq("dw.orders"))).thenReturn(TableExistence.PRESENT);
        when(cat.probeExistence(anyLong(), anyLong(), eq("tmp_stage"))).thenReturn(TableExistence.ABSENT);
        when(cat.probeExistence(anyLong(), anyLong(), eq("legacy.audit"))).thenReturn(TableExistence.ABSENT);
        when(cat.probeExistence(anyLong(), anyLong(), eq("maybe_tbl"))).thenReturn(TableExistence.UNKNOWN);

        List<IoEdge> io = List.of(
                io("dw.orders", Source.SCRIPT_AGENT, Confidence.UNVERIFIED),        // PRESENT → ADOPTED + CONFIRMED
                io("tmp_stage", Source.SCRIPT_AGENT, Confidence.UNVERIFIED),        // ABSENT 推断类 → DROPPED
                io("legacy.audit", Source.SQL_PARSED, Confidence.UNVERIFIED),       // ABSENT 确定性 → RETAINED
                io("maybe_tbl", Source.SCRIPT_MODEL, Confidence.UNVERIFIED),        // UNKNOWN → RETAINED（无留痕）
                io("information_schema.columns", Source.SCRIPT_INFERRED, Confidence.UNVERIFIED), // SYSTEM 推断类 → EXCLUDED
                io("information_schema.tables", Source.SQL_PARSED, Confidence.UNVERIFIED)        // SYSTEM 确定性 → RETAINED
        );
        // 列级边：源表 tmp_stage 被剔 → 连带剔除；dw.orders→dw.orders 保留
        List<ColumnEdge> col = List.of(
                new ColumnEdge(ref("tmp_stage"), "a", ref("dw.orders"), "x", Transform.DIRECT, Confidence.UNVERIFIED),
                new ColumnEdge(ref("dw.orders"), "x", ref("dw.orders"), "y", Transform.DIRECT, Confidence.UNVERIFIED)
        );

        CatalogGroundingService.GroundingResult r = svc.ground(1L, 1L, 100L, io, col,
                cat, 7L, "POSTGRES", cat, 7L, "POSTGRES");

        // 保留的表边
        List<String> keptQn = r.ioEdges().stream().map(e -> e.table().qualifiedName()).collect(Collectors.toList());
        assertThat(keptQn).containsExactlyInAnyOrder(
                "dw.orders", "legacy.audit", "maybe_tbl", "information_schema.tables");
        assertThat(keptQn).doesNotContain("tmp_stage", "information_schema.columns");

        // PRESENT 采纳边升 CONFIRMED（catalog-verified）
        IoEdge orders = r.ioEdges().stream().filter(e -> e.table().qualifiedName().equals("dw.orders")).findFirst().orElseThrow();
        assertThat(orders.confidence()).isEqualTo(Confidence.CONFIRMED);
        // 确定性 ABSENT 保留边不升级
        IoEdge audit = r.ioEdges().stream().filter(e -> e.table().qualifiedName().equals("legacy.audit")).findFirst().orElseThrow();
        assertThat(audit.confidence()).isEqualTo(Confidence.UNVERIFIED);

        // 列级边连带剔除
        assertThat(r.columnEdges()).hasSize(1);
        assertThat(r.columnEdges().get(0).srcTable().qualifiedName()).isEqualTo("dw.orders");

        // 处置留痕：UNKNOWN 不落，其余 5 条落
        Map<String, String> byCandidate = r.dispositions().stream()
                .collect(Collectors.toMap(GroundingDisposition::candidate, GroundingDisposition::disposition));
        assertThat(byCandidate).containsOnlyKeys(
                "dw.orders", "tmp_stage", "legacy.audit", "information_schema.columns", "information_schema.tables");
        assertThat(byCandidate.get("dw.orders")).isEqualTo("ADOPTED");
        assertThat(byCandidate.get("tmp_stage")).isEqualTo("DROPPED");
        assertThat(byCandidate.get("legacy.audit")).isEqualTo("RETAINED");
        assertThat(byCandidate.get("information_schema.columns")).isEqualTo("EXCLUDED");
        assertThat(byCandidate.get("information_schema.tables")).isEqualTo("RETAINED");
    }

    @Test
    void deterministic_sources_never_dropped_even_when_absent() {
        DatasourceBoundCatalog cat = mock(DatasourceBoundCatalog.class);
        when(cat.probeExistence(anyLong(), anyLong(), eq("x.ghost"))).thenReturn(TableExistence.ABSENT);

        for (Source s : new Source[]{Source.SQL_PARSED, Source.SCRIPT_SQL, Source.AGENT, Source.FORM}) {
            CatalogGroundingService.GroundingResult r = svc.ground(1L, 1L, 1L,
                    List.of(io("x.ghost", s, Confidence.UNVERIFIED)), List.of(),
                    cat, 1L, "POSTGRES", cat, 1L, "POSTGRES");
            assertThat(r.ioEdges()).as("deterministic source %s must be retained on ABSENT", s).hasSize(1);
        }
    }

    @Test
    void unbound_catalog_null_retains_everything() {
        // catalog=null（未绑定数据源）→ 全 UNKNOWN → 原样保留，零留痕
        CatalogGroundingService.GroundingResult r = svc.ground(1L, 1L, 1L,
                List.of(io("tmp_stage", Source.SCRIPT_AGENT, Confidence.UNVERIFIED)), List.of(),
                null, null, null, null, null, null);
        assertThat(r.ioEdges()).hasSize(1);
        assertThat(r.dispositions()).isEmpty();
    }
}
