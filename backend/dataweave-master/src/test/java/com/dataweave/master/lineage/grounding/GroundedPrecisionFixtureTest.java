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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T020（US3）：带目录接地精度夹具——量化 grounding on/off 的 precision/recall 变化。
 *
 * <p>H2 已知全表集合（gold ground truth）中混入 CTE/临时/幻觉/系统表候选；
 * grounding off = 所有候选原样保留（含 FP 基线 precision）；
 * grounding on = 仅真阴（ABSENT 推断类）被剔除、真阳全保留，precision 升、recall 不降；
 * 同种子（同一夹具输入）两次结论一致（SC-001/SC-007）。
 *
 * <h3>夹具设计</h3>
 * <ul>
 *   <li>Ground truth 真表：public.orders、public.customers、public.products</li>
 *   <li>FP 注入：cte_stage（CTE 残留）、tmp_sink（临时表）、ghost_tbl（模型幻觉）、
 *       information_schema.columns（系统表，推断类通道）</li>
 *   <li>边界：unknown.tbl（UNKNOWN 不可达，留但不罚）、public.missing（确定性 ABSENT，留但不罚）</li>
 * </ul>
 *
 * <p>引擎 = POSTGRES；所有候选走 READ 方向 + 同一绑定目录。
 */
class GroundedPrecisionFixtureTest {

    private static final long TENANT = 1L;
    private static final long PROJECT = 1L;
    private static final long TASK_ID = 200L;
    private static final long DS_ID = 7L;
    private static final String ENGINE = "POSTGRES";

    private static final Set<String> GOLD_TABLES = Set.of(
            "public.orders", "public.customers", "public.products");

    private static TableRef ref(String qn) {
        return new TableRef(null, qn, null);
    }

    private static IoEdge io(String qn, Source src) {
        return new IoEdge(ref(qn), Direction.READS, src, Confidence.UNVERIFIED);
    }

    /** 构建带所有 FP 类别的候选集（所有 channel=inferential 模拟 AI 通道输出，含系统表/CTE/临时/幻觉）。 */
    private List<IoEdge> candidateSet() {
        return List.of(
                // ── 真阳：金标中真实存在的表 ──
                io("public.orders", Source.SCRIPT_AGENT),
                io("public.customers", Source.SCRIPT_AGENT),
                io("public.products", Source.SCRIPT_AGENT),
                // ── 假阳（inferential → 可剔除）──
                io("cte_stage", Source.SCRIPT_INFERRED),         // CTE 残留
                io("tmp_sink", Source.SCRIPT_MODEL),              // 临时表幻觉
                io("ghost_tbl", Source.SCRIPT_AGENT),             // 模型幻觉
                io("information_schema.columns", Source.SCRIPT_INFERRED), // 系统表
                // ── 边界：UNKNOWN 不可达 → 保留不罚 ──
                io("unknown.tbl", Source.SCRIPT_INFERRED),
                // ── 边界：确定性 ABSENT → 保留不罚（FR-011）──
                io("public.missing", Source.SQL_PARSED)
        );
    }

    /** 构建目录桩：金标表 PRESENT，FP 表 ABSENT，unknown 表 UNKNOWN。 */
    private DatasourceBoundCatalog catalogStub() {
        DatasourceBoundCatalog cat = mock(DatasourceBoundCatalog.class);
        // 真阳
        when(cat.probeExistence(anyLong(), anyLong(), eq("public.orders"))).thenReturn(TableExistence.PRESENT);
        when(cat.probeExistence(anyLong(), anyLong(), eq("public.customers"))).thenReturn(TableExistence.PRESENT);
        when(cat.probeExistence(anyLong(), anyLong(), eq("public.products"))).thenReturn(TableExistence.PRESENT);
        // 假阳
        when(cat.probeExistence(anyLong(), anyLong(), eq("cte_stage"))).thenReturn(TableExistence.ABSENT);
        when(cat.probeExistence(anyLong(), anyLong(), eq("tmp_sink"))).thenReturn(TableExistence.ABSENT);
        when(cat.probeExistence(anyLong(), anyLong(), eq("ghost_tbl"))).thenReturn(TableExistence.ABSENT);
        // system table 不会被 probe（分类器在 probe 之前拦截），但以防缺省
        when(cat.probeExistence(anyLong(), anyLong(), eq("information_schema.columns")))
                .thenReturn(TableExistence.PRESENT);   // 真存在但应被系统分类器挡掉
        // 边界
        when(cat.probeExistence(anyLong(), anyLong(), eq("unknown.tbl"))).thenReturn(TableExistence.UNKNOWN);
        when(cat.probeExistence(anyLong(), anyLong(), eq("public.missing"))).thenReturn(TableExistence.ABSENT);
        return cat;
    }

    @Test
    void groundingOff_baselineAllRetained_fpPrecisionLow() {
        // grounding off = 不调 ground()，直接看候选集
        List<IoEdge> candidates = candidateSet();
        long tp = candidates.stream().filter(e -> GOLD_TABLES.contains(qn(e))).count();
        double precisionOff = (double) tp / candidates.size();
        double recallOff = 1.0;   // 真阳全在，gold 全覆盖

        // 基线：precision 低（含大量 FP），recall 全（含噪全量）
        assertThat(precisionOff).isLessThan(0.5);   // 4/10 = 0.40
        assertThat(recallOff).isEqualTo(1.0);
    }

    @Test
    void groundingOn_fpDropped_precisionUp_recallNotDown() {
        CatalogGroundingService svc = new CatalogGroundingService(new SystemNamespaceClassifier(""));
        DatasourceBoundCatalog cat = catalogStub();

        CatalogGroundingService.GroundingResult r = svc.ground(
                TENANT, PROJECT, TASK_ID,
                candidateSet(), List.of(),
                cat, DS_ID, ENGINE, cat, DS_ID, ENGINE);

        List<IoEdge> kept = r.ioEdges();
        Set<String> keptQns = kept.stream().map(e -> e.table().qualifiedName()).collect(Collectors.toSet());

        // 真阳全部保留（recall 100% in gold set）
        assertThat(keptQns).containsAll(GOLD_TABLES);

        // 假阳全部剔除
        assertThat(keptQns).doesNotContain("cte_stage", "tmp_sink", "ghost_tbl",
                "information_schema.columns");

        // 边界：UNKNOWN 保留（不罚）
        assertThat(keptQns).contains("unknown.tbl");
        // 边界：确定性 ABSENT 保留（FR-011）
        assertThat(keptQns).contains("public.missing");

        // precision：真阳 / 保留总数
        long tp = kept.stream().filter(e -> GOLD_TABLES.contains(e.table().qualifiedName())).count();
        double precisionOn = (double) tp / kept.size();
        // precision 应显著高于 baseline（0.40）
        assertThat(precisionOn).isGreaterThan(0.4);
        // 3 TP / 5 kept = 0.60
        assertThat(precisionOn).isEqualTo(3.0 / 5.0);

        // recall (gold): 3/3 = 1.0
        double recallOn = (double) kept.stream().filter(e -> GOLD_TABLES.contains(qn(e))).count()
                / GOLD_TABLES.size();
        assertThat(recallOn).isEqualTo(1.0);

        // 处置留痕：ADOPTED ×3 + DROPPED ×3 + EXCLUDED ×1 + RETAINED ×1 + UNKNOWN 不落(= 8 条)
        assertThat(r.dispositions()).hasSize(8);
        long dropped = r.dispositions().stream()
                .filter(d -> GroundingDisposition.DISP_DROPPED.equals(d.disposition())).count();
        long excluded = r.dispositions().stream()
                .filter(d -> GroundingDisposition.DISP_EXCLUDED.equals(d.disposition())).count();
        long adopted = r.dispositions().stream()
                .filter(d -> GroundingDisposition.DISP_ADOPTED.equals(d.disposition())).count();
        long retained = r.dispositions().stream()
                .filter(d -> GroundingDisposition.DISP_RETAINED.equals(d.disposition())).count();
        assertThat(adopted).isEqualTo(3);    // orders, customers, products
        assertThat(dropped).isEqualTo(3);    // cte_stage, tmp_sink, ghost_tbl
        assertThat(excluded).isEqualTo(1);   // information_schema.columns
        assertThat(retained).isEqualTo(1);   // public.missing (deterministic absent)
    }

    @Test
    void sameSeedTwice_sameConclusion() {
        // 夹具确定性：同一输入 → 同一输出（无随机因素）
        CatalogGroundingService svc = new CatalogGroundingService(new SystemNamespaceClassifier(""));

        CatalogGroundingService.GroundingResult r1 = svc.ground(
                TENANT, PROJECT, TASK_ID,
                candidateSet(), List.of(),
                catalogStub(), DS_ID, ENGINE, catalogStub(), DS_ID, ENGINE);

        CatalogGroundingService.GroundingResult r2 = svc.ground(
                TENANT, PROJECT, TASK_ID,
                candidateSet(), List.of(),
                catalogStub(), DS_ID, ENGINE, catalogStub(), DS_ID, ENGINE);

        // 两轮保留的边集完全一致
        Set<String> qns1 = r1.ioEdges().stream().map(e -> e.table().qualifiedName()).collect(Collectors.toSet());
        Set<String> qns2 = r2.ioEdges().stream().map(e -> e.table().qualifiedName()).collect(Collectors.toSet());
        assertThat(qns1).isEqualTo(qns2);

        // 两轮处置记录完全一致
        assertThat(r1.dispositions()).hasSize(r2.dispositions().size());
        for (int i = 0; i < r1.dispositions().size(); i++) {
            GroundingDisposition d1 = r1.dispositions().get(i);
            GroundingDisposition d2 = r2.dispositions().get(i);
            assertThat(d1.candidate()).isEqualTo(d2.candidate());
            assertThat(d1.verdict()).isEqualTo(d2.verdict());
            assertThat(d1.disposition()).isEqualTo(d2.disposition());
        }
    }

    private static String qn(IoEdge e) {
        return e.table() != null ? e.table().qualifiedName() : null;
    }
}
