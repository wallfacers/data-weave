package com.dataweave.master.application.authoring;

import java.util.List;
import java.util.Optional;

import com.dataweave.master.application.LineageQueryService;
import com.dataweave.master.application.lineage.DatasourceBoundCatalog;
import com.dataweave.master.application.lineage.TaskLineageResolver;
import com.dataweave.master.application.lineage.grounding.CatalogGroundingService;
import com.dataweave.master.application.lineage.grounding.TableExistence;
import com.dataweave.master.application.lineage.script.ScriptLineageService;
import com.dataweave.master.domain.TaskDef;
import com.dataweave.master.domain.TaskDefRepository;
import com.dataweave.master.domain.WorkflowEdgeRepository;
import com.dataweave.master.domain.WorkflowNodeRepository;
import com.dataweave.master.domain.lineage.Confidence;
import com.dataweave.master.domain.lineage.DatasourceCoord;
import com.dataweave.master.domain.lineage.Direction;
import com.dataweave.master.domain.lineage.IoEdge;
import com.dataweave.master.domain.lineage.Source;
import com.dataweave.master.domain.lineage.TableRef;
import com.dataweave.master.lineage.LineageGraph;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 058 T008（US1 / SC-005 / FR-005）：防幻觉 + 降级。
 * ① 存在性未知（UNKNOWN）→ 标 INFERRED，绝不虚构接地/上游；
 * ② 血缘图查不可达 → 邻居空 + partial 标注 lineage-graph（不整体失败）；
 * ③ 存在性探测抛异常 → partial 标注 grounding + 回退 INFERRED（不崩）。
 */
class AuthoringContextGroundingTest {

    private final DatasourceCoord coord = new DatasourceCoord(1L, 1L, null, null, null, "dsA");

    private TableRef table(String qn) {
        return new TableRef(coord, qn, null);
    }

    private TaskDefRepository taskDefsWithSql() {
        var taskDefs = mock(TaskDefRepository.class);
        TaskDef def = new TaskDef();
        def.setId(10L);
        def.setTenantId(1L);
        def.setProjectId(1L);
        def.setType("SQL");
        def.setContent("SELECT * FROM ods.user");
        def.setDatasourceId(5L);
        when(taskDefs.findById(10L)).thenReturn(Optional.of(def));
        return taskDefs;
    }

    private TaskLineageResolver resolverReadingOdsUser() {
        var resolver = mock(TaskLineageResolver.class);
        var io = List.of(new IoEdge(table("ods.user"), Direction.READS, Source.SQL_PARSED, Confidence.UNVERIFIED));
        when(resolver.resolve(eq(1L), eq(1L), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new TaskLineageResolver.ResolvedLineage(io, List.of(), true));
        return resolver;
    }

    private AuthoringContextService service(LineageQueryService lineage, TaskLineageResolver resolver,
                                            TaskDefRepository taskDefs) {
        return new AuthoringContextService(lineage, mock(ScriptLineageService.class),
                mock(CatalogGroundingService.class), resolver,
                mock(WorkflowEdgeRepository.class), mock(WorkflowNodeRepository.class), taskDefs);
    }

    @Test
    void unknownExistenceIsInferredNotFabricated() {
        var resolver = resolverReadingOdsUser();
        var cat = mock(DatasourceBoundCatalog.class);
        when(resolver.catalogFor(any())).thenReturn(cat);
        when(cat.probeExistence(anyLong(), anyLong(), any())).thenReturn(TableExistence.UNKNOWN);
        var lineage = mock(LineageQueryService.class);
        when(lineage.upstream(eq(1L), eq(1L), any(), anyInt(), any(), any(), any(), any(), any()))
                .thenReturn(LineageGraph.empty());

        var ctx = service(lineage, resolver, taskDefsWithSql()).context(1L, 1L, "10", 3);

        assertThat(ctx.reads()).singleElement().satisfies(r -> {
            assertThat(r.groundingState()).isEqualTo("INFERRED"); // 未知不谎称存在
            assertThat(r.neighbors()).isEmpty();                  // 不虚构上游
        });
    }

    @Test
    void unreachableLineageGraphDegradesToPartial() {
        var resolver = resolverReadingOdsUser();
        var cat = mock(DatasourceBoundCatalog.class);
        when(resolver.catalogFor(any())).thenReturn(cat);
        when(cat.probeExistence(anyLong(), anyLong(), any())).thenReturn(TableExistence.PRESENT);
        var lineage = mock(LineageQueryService.class);
        when(lineage.upstream(eq(1L), eq(1L), any(), anyInt(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("neo4j down"));

        var ctx = service(lineage, resolver, taskDefsWithSql()).context(1L, 1L, "10", 3);

        assertThat(ctx.reads()).singleElement().satisfies(r -> assertThat(r.neighbors()).isEmpty());
        assertThat(ctx.partial()).extracting(AuthoringContext.MissingNote::source).contains("lineage-graph");
    }

    @Test
    void groundingProbeFailureDegradesToInferred() {
        var resolver = resolverReadingOdsUser();
        when(resolver.catalogFor(any())).thenThrow(new RuntimeException("catalog build failed"));
        var lineage = mock(LineageQueryService.class);
        when(lineage.upstream(eq(1L), eq(1L), any(), anyInt(), any(), any(), any(), any(), any()))
                .thenReturn(LineageGraph.empty());

        var ctx = service(lineage, resolver, taskDefsWithSql()).context(1L, 1L, "10", 3);

        assertThat(ctx.reads()).singleElement().satisfies(r -> assertThat(r.groundingState()).isEqualTo("INFERRED"));
        assertThat(ctx.partial()).extracting(AuthoringContext.MissingNote::source).contains("grounding");
    }
}
