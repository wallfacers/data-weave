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
import com.dataweave.master.domain.lineage.ColumnEdge;
import com.dataweave.master.domain.lineage.Confidence;
import com.dataweave.master.domain.lineage.DatasourceCoord;
import com.dataweave.master.domain.lineage.Direction;
import com.dataweave.master.domain.lineage.IoEdge;
import com.dataweave.master.domain.lineage.Source;
import com.dataweave.master.domain.lineage.TableRef;
import com.dataweave.master.domain.lineage.Transform;
import com.dataweave.master.lineage.FlowEdgeView;
import com.dataweave.master.lineage.GraphNodeView;
import com.dataweave.master.lineage.LineageGraph;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 058 T010（US1）：{@link AuthoringContextService#context} 装配单测（mock 层，脱离 neo4j）。
 * 验证读写表→上下游表邻居 + 三态接地 + 列血缘直取，及租户隔离/防幻觉的确定性编排。
 * 真实 neo4j+数据源夹具的端到端 IT 见 T006（AuthoringContextServiceIT，留待图库夹具）。
 */
class AuthoringContextServiceTest {

    private final DatasourceCoord coord = new DatasourceCoord(1L, 1L, null, null, null, "dsA");

    private TableRef table(String qn) {
        return new TableRef(coord, qn, null);
    }

    private AuthoringContextService newService(LineageQueryService lineage, TaskLineageResolver resolver,
                                               TaskDefRepository taskDefs) {
        return new AuthoringContextService(lineage, mock(ScriptLineageService.class),
                mock(CatalogGroundingService.class), resolver,
                mock(WorkflowEdgeRepository.class), mock(WorkflowNodeRepository.class), taskDefs);
    }

    private TaskDef sqlTask() {
        TaskDef def = new TaskDef();
        def.setId(10L);
        def.setTenantId(1L);
        def.setProjectId(1L);
        def.setType("SQL");
        def.setContent("INSERT INTO dw.user_daily SELECT * FROM ods.user");
        def.setDatasourceId(5L);
        def.setTargetDatasourceId(6L);
        return def;
    }

    @Test
    void assemblesReadsWritesWithGroundingColumnsAndUpstreamNeighbor() {
        var lineage = mock(LineageQueryService.class);
        var resolver = mock(TaskLineageResolver.class);
        var taskDefs = mock(TaskDefRepository.class);
        when(taskDefs.findById(10L)).thenReturn(Optional.of(sqlTask()));

        // 抽取产物：读 ods.user、写 dw.user_daily + 一条列边
        var io = List.of(
                new IoEdge(table("ods.user"), Direction.READS, Source.SQL_PARSED, Confidence.UNVERIFIED),
                new IoEdge(table("dw.user_daily"), Direction.WRITES, Source.SQL_PARSED, Confidence.UNVERIFIED));
        var cols = List.of(new ColumnEdge(table("ods.user"), "id", table("dw.user_daily"), "id",
                Transform.DIRECT, Confidence.UNVERIFIED));
        when(resolver.resolve(eq(1L), eq(1L), eq(10L), eq("SQL"), any(), eq(5L), eq(6L), any(), any()))
                .thenReturn(new TaskLineageResolver.ResolvedLineage(io, cols, true));

        // 三态接地：读表存在 / 写表缺失
        var cat5 = mock(DatasourceBoundCatalog.class);
        var cat6 = mock(DatasourceBoundCatalog.class);
        when(resolver.catalogFor(5L)).thenReturn(cat5);
        when(resolver.catalogFor(6L)).thenReturn(cat6);
        when(cat5.probeExistence(1L, 1L, "ods.user")).thenReturn(TableExistence.PRESENT);
        when(cat6.probeExistence(1L, 1L, "dw.user_daily")).thenReturn(TableExistence.ABSENT);

        // 读表 ods.user 的上游图：src.raw --FLOWS_TO--> ods.user
        String seed = coord.dsKey() + "|ods.user";
        String up = coord.dsKey() + "|src.raw";
        var upstreamGraph = new LineageGraph(
                List.of(new GraphNodeView(seed, GraphNodeView.NodeType.TABLE, "ods.user", null, GraphNodeView.Granularity.TABLE),
                        new GraphNodeView(up, GraphNodeView.NodeType.TABLE, "src.raw", null, GraphNodeView.Granularity.TABLE)),
                List.of(new FlowEdgeView(up, seed, GraphNodeView.Granularity.TABLE)),
                GraphNodeView.Granularity.TABLE, 3, false, null);
        when(lineage.upstream(eq(1L), eq(1L), eq(seed), anyInt(), eq(GraphNodeView.Granularity.TABLE),
                any(), any(), any(), any())).thenReturn(upstreamGraph);
        when(lineage.downstream(eq(1L), eq(1L), any(), anyInt(), any(), any(), any(), any(), any()))
                .thenReturn(LineageGraph.empty());

        var ctx = newService(lineage, resolver, taskDefs).context(1L, 1L, "10", 3);

        // 读写分列 + 接地态
        assertThat(ctx.reads()).singleElement().satisfies(r -> {
            assertThat(r.table()).isEqualTo("ods.user");
            assertThat(r.direction()).isEqualTo("READS");
            assertThat(r.groundingState()).isEqualTo("PRESENT");
            assertThat(r.neighbors()).singleElement().satisfies(n -> {
                assertThat(n.name()).isEqualTo("src.raw");
                assertThat(n.kind()).isEqualTo("TABLE");
                assertThat(n.hop()).isEqualTo(1);
            });
        });
        assertThat(ctx.writes()).singleElement().satisfies(w -> {
            assertThat(w.table()).isEqualTo("dw.user_daily");
            assertThat(w.groundingState()).isEqualTo("UNGROUNDED"); // ABSENT→未接地，不虚构
            assertThat(w.neighbors()).isEmpty();
        });
        // 列血缘直取
        assertThat(ctx.columnLineage()).singleElement().satisfies(c -> {
            assertThat(c.srcTable()).isEqualTo("ods.user");
            assertThat(c.dstColumn()).isEqualTo("id");
        });
        assertThat(ctx.depthUsed()).isEqualTo(3);
        assertThat(ctx.partial()).isEmpty();
    }

    @Test
    void unknownTaskYieldsPartialNotEmptyFailure() {
        var taskDefs = mock(TaskDefRepository.class);
        when(taskDefs.findById(99L)).thenReturn(Optional.empty());

        var ctx = newService(mock(LineageQueryService.class), mock(TaskLineageResolver.class), taskDefs)
                .context(1L, 1L, "99", 3);

        assertThat(ctx.reads()).isEmpty();
        assertThat(ctx.writes()).isEmpty();
        assertThat(ctx.partial()).extracting(AuthoringContext.MissingNote::source).contains("task");
    }

    @Test
    void crossProjectTaskIsIsolatedAsMissing() {
        var taskDefs = mock(TaskDefRepository.class);
        TaskDef other = sqlTask();
        other.setProjectId(2L); // 非请求项目
        when(taskDefs.findById(10L)).thenReturn(Optional.of(other));

        var ctx = newService(mock(LineageQueryService.class), mock(TaskLineageResolver.class), taskDefs)
                .context(1L, 1L, "10", 3);

        assertThat(ctx.reads()).isEmpty();
        assertThat(ctx.partial()).extracting(AuthoringContext.MissingNote::source).contains("task");
    }
}
