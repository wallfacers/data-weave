package com.dataweave.master.application.lineage.script;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.dataweave.master.application.lineage.LineageEdgeAssembler;
import com.dataweave.master.domain.lineage.Confidence;
import com.dataweave.master.domain.lineage.DatasourceCoord;
import com.dataweave.master.domain.lineage.Direction;
import com.dataweave.master.domain.lineage.IoEdge;
import com.dataweave.master.domain.lineage.Source;
import com.dataweave.master.domain.lineage.TableRef;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 041 T004/T018：编排器聚合 / 三级冲突消解 / 超时降级 / 裁决重放。
 */
class ScriptLineageServiceTest {

    private static final DatasourceCoord COORD = new DatasourceCoord(1L, 1L, null, null, null, null);

    private LineageEdgeAssembler mockAssembler() {
        LineageEdgeAssembler assembler = mock(LineageEdgeAssembler.class);
        when(assembler.resolveCoord(anyLong(), anyLong(), any())).thenReturn(COORD);
        when(assembler.tableRef(any(), anyString())).thenAnswer(inv ->
                new TableRef(inv.getArgument(0), inv.getArgument(1), null));
        return assembler;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ScriptLineageCorrectionGate> gate(ScriptLineageCorrectionGate g) {
        ObjectProvider<ScriptLineageCorrectionGate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(g);
        return provider;
    }


    @SuppressWarnings("unchecked")
    private static ObjectProvider<com.dataweave.master.domain.lineage.LineageHintRepository> noHints() {
        ObjectProvider<com.dataweave.master.domain.lineage.LineageHintRepository> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    private static ScriptLineageExtractor fixed(Source channel, Set<String> reads, Set<String> writes) {
        return new ScriptLineageExtractor() {
            @Override
            public boolean supports(String taskType) {
                return true;
            }

            @Override
            public ScriptExtraction extract(ScriptSource source) {
                return new ScriptExtraction(reads, writes, List.of(), List.of(), channel,
                        channel == Source.SCRIPT_MODEL ? "m@v1" : null);
            }
        };
    }

    private static ScriptSource src() {
        return new ScriptSource(1L, 1L, 7L, "PYTHON", "print('x')", null, null);
    }

    @Test
    void aggregatesMultipleExtractors() {
        ScriptLineageService svc = new ScriptLineageService(
                List.of(fixed(Source.SCRIPT_SQL, Set.of("ods.a"), Set.of("dw.b")),
                        fixed(Source.SCRIPT_INFERRED, Set.of(), Set.of("dw.c"))),
                mockAssembler(), gate(null), noHints(), 2000);
        ScriptLineageService.Result r = svc.extract(src());
        assertThat(r.ioEdges()).hasSize(3);
        assertThat(r.ioEdges()).anyMatch(e -> e.direction() == Direction.READS
                && e.table().qualifiedName().equals("ods.a") && e.source() == Source.SCRIPT_SQL
                && e.confidence() == Confidence.CONFIRMED);
        assertThat(r.ioEdges()).anyMatch(e -> e.direction() == Direction.WRITES
                && e.table().qualifiedName().equals("dw.c") && e.source() == Source.SCRIPT_INFERRED
                && e.confidence() == Confidence.UNVERIFIED);
    }

    @Test
    void conflictResolutionSqlBeatsInferredBeatsModel() {
        ScriptLineageService svc = new ScriptLineageService(
                List.of(fixed(Source.SCRIPT_MODEL, Set.of(), Set.of("dw.t")),
                        fixed(Source.SCRIPT_INFERRED, Set.of(), Set.of("dw.t")),
                        fixed(Source.SCRIPT_SQL, Set.of(), Set.of("dw.t"))),
                mockAssembler(), gate(null), noHints(), 2000);
        ScriptLineageService.Result r = svc.extract(src());
        assertThat(r.ioEdges()).hasSize(1);
        IoEdge e = r.ioEdges().get(0);
        assertThat(e.source()).isEqualTo(Source.SCRIPT_SQL);
        assertThat(e.confidence()).isEqualTo(Confidence.CONFIRMED);
        assertThat(e.modelVersion()).isNull();
    }

    @Test
    void modelEdgeCarriesModelVersion() {
        ScriptLineageService svc = new ScriptLineageService(
                List.of(fixed(Source.SCRIPT_MODEL, Set.of(), Set.of("dw.only_model"))),
                mockAssembler(), gate(null), noHints(), 2000);
        ScriptLineageService.Result r = svc.extract(src());
        assertThat(r.ioEdges()).singleElement().satisfies(e -> {
            assertThat(e.source()).isEqualTo(Source.SCRIPT_MODEL);
            assertThat(e.modelVersion()).isEqualTo("m@v1");
        });
    }

    @Test
    void slowExtractorTimesOutButOthersSurvive() {
        ScriptLineageExtractor slow = new ScriptLineageExtractor() {
            @Override
            public boolean supports(String taskType) {
                return true;
            }

            @Override
            public ScriptExtraction extract(ScriptSource source) {
                try {
                    Thread.sleep(60_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return ScriptExtraction.empty(Source.SCRIPT_MODEL);
            }
        };
        ScriptLineageService svc = new ScriptLineageService(
                List.of(fixed(Source.SCRIPT_SQL, Set.of("ods.a"), Set.of()), slow),
                mockAssembler(), gate(null), noHints(), 300);
        long t0 = System.currentTimeMillis();
        ScriptLineageService.Result r = svc.extract(src());
        assertThat(System.currentTimeMillis() - t0).isLessThan(5_000);
        assertThat(r.ioEdges()).hasSize(1);
        assertThat(r.hints()).anyMatch(h -> h.kind() == ScriptExtraction.HintKind.TIMEOUT);
    }

    @Test
    void throwingExtractorDegradesWithHint() {
        ScriptLineageExtractor bad = new ScriptLineageExtractor() {
            @Override
            public boolean supports(String taskType) {
                return true;
            }

            @Override
            public ScriptExtraction extract(ScriptSource source) {
                throw new IllegalStateException("boom");
            }
        };
        ScriptLineageService svc = new ScriptLineageService(
                List.of(bad, fixed(Source.SCRIPT_SQL, Set.of("ods.a"), Set.of())),
                mockAssembler(), gate(null), noHints(), 2000);
        ScriptLineageService.Result r = svc.extract(src());
        assertThat(r.ioEdges()).hasSize(1);
        assertThat(r.hints()).anyMatch(h -> h.kind() == ScriptExtraction.HintKind.PARSE_FAIL);
    }

    // ── T018 裁决重放 ──

    @Test
    void removedDecisionSuppressesEdge() {
        String tableKey = COORD.dsKey() + "|dw.bad";
        ScriptLineageCorrectionGate g = (t, p, task) ->
                Map.of("WRITE|" + tableKey, ScriptLineageCorrectionGate.STATUS_REMOVED);
        ScriptLineageService svc = new ScriptLineageService(
                List.of(fixed(Source.SCRIPT_INFERRED, Set.of("ods.a"), Set.of("dw.bad"))),
                mockAssembler(), gate(g), noHints(), 2000);
        ScriptLineageService.Result r = svc.extract(src());
        assertThat(r.ioEdges()).hasSize(1);
        assertThat(r.ioEdges().get(0).direction()).isEqualTo(Direction.READS);
    }

    @Test
    void confirmedDecisionUpgradesConfidence() {
        String tableKey = COORD.dsKey() + "|dw.ok";
        ScriptLineageCorrectionGate g = (t, p, task) ->
                Map.of("WRITE|" + tableKey, ScriptLineageCorrectionGate.STATUS_CONFIRMED);
        ScriptLineageService svc = new ScriptLineageService(
                List.of(fixed(Source.SCRIPT_INFERRED, Set.of(), Set.of("dw.ok"))),
                mockAssembler(), gate(g), noHints(), 2000);
        ScriptLineageService.Result r = svc.extract(src());
        assertThat(r.ioEdges()).singleElement().satisfies(e -> {
            assertThat(e.confidence()).isEqualTo(Confidence.CONFIRMED);
            assertThat(e.source()).isEqualTo(Source.SCRIPT_INFERRED);
        });
    }

    @Test
    void revokedNoDecisionRestoresNaturalResult() {
        ScriptLineageService svc = new ScriptLineageService(
                List.of(fixed(Source.SCRIPT_INFERRED, Set.of(), Set.of("dw.ok"))),
                mockAssembler(), gate((t, p, task) -> Map.of()), noHints(), 2000);
        ScriptLineageService.Result r = svc.extract(src());
        assertThat(r.ioEdges()).singleElement().satisfies(e ->
                assertThat(e.confidence()).isEqualTo(Confidence.UNVERIFIED));
    }

    @Test
    void handlesOnlyScriptTypes() {
        ScriptLineageService svc = new ScriptLineageService(List.of(), mockAssembler(), gate(null), noHints(), 2000);
        assertThat(svc.handles("PYTHON")).isTrue();
        assertThat(svc.handles("SHELL")).isTrue();
        assertThat(svc.handles("SPARK")).isTrue();
        assertThat(svc.handles("SQL")).isFalse();
        assertThat(svc.handles("ECHO")).isFalse();
        assertThat(svc.handles(null)).isFalse();
    }
}
