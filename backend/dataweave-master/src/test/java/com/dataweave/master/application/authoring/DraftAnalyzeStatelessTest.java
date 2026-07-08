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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 058 T007（US1 / FR-004）：工作副本草稿分析——无副作用 + 与已 push 等价。
 * ① contextForDraft 不读 DB（never findById）、零持久化；
 * ② 同 content 经草稿路径与已 push 路径产出相同读写表（抽取语义等价）。
 */
class DraftAnalyzeStatelessTest {

    private static final String CONTENT = "INSERT INTO dw.user_daily SELECT * FROM ods.user";
    private final DatasourceCoord coord = new DatasourceCoord(1L, 1L, null, null, null, "dsA");

    private TableRef table(String qn) {
        return new TableRef(coord, qn, null);
    }

    private TaskLineageResolver resolverStub() {
        var resolver = mock(TaskLineageResolver.class);
        var io = List.of(
                new IoEdge(table("ods.user"), Direction.READS, Source.SQL_PARSED, Confidence.UNVERIFIED),
                new IoEdge(table("dw.user_daily"), Direction.WRITES, Source.SQL_PARSED, Confidence.UNVERIFIED));
        var resolved = new TaskLineageResolver.ResolvedLineage(io, List.of(), true);
        // 已 push（taskDefId=10）与草稿（taskDefId=null）同 content → 同抽取产物
        when(resolver.resolve(eq(1L), eq(1L), eq(10L), eq("SQL"), eq(CONTENT), eq(5L), eq(6L), any(), any()))
                .thenReturn(resolved);
        when(resolver.resolve(eq(1L), eq(1L), isNull(), eq("SQL"), eq(CONTENT), eq(5L), eq(6L), any(), any()))
                .thenReturn(resolved);
        var cat = mock(DatasourceBoundCatalog.class);
        when(resolver.catalogFor(any())).thenReturn(cat);
        when(cat.probeExistence(anyLong(), anyLong(), any())).thenReturn(TableExistence.UNKNOWN);
        return resolver;
    }

    private LineageQueryService emptyLineage() {
        var lineage = mock(LineageQueryService.class);
        when(lineage.upstream(eq(1L), eq(1L), any(), anyInt(), any(), any(), any(), any(), any()))
                .thenReturn(LineageGraph.empty());
        when(lineage.downstream(eq(1L), eq(1L), any(), anyInt(), any(), any(), any(), any(), any()))
                .thenReturn(LineageGraph.empty());
        return lineage;
    }

    private AuthoringContextService service(LineageQueryService lineage, TaskLineageResolver resolver,
                                            TaskDefRepository taskDefs) {
        return new AuthoringContextService(lineage, mock(ScriptLineageService.class),
                mock(CatalogGroundingService.class), resolver,
                mock(WorkflowEdgeRepository.class), mock(WorkflowNodeRepository.class), taskDefs);
    }

    @Test
    void draftAnalysisIsStatelessAndDoesNotTouchDb() {
        var taskDefs = mock(TaskDefRepository.class);
        var svc = service(emptyLineage(), resolverStub(), taskDefs);

        var ctx = svc.contextForDraft(1L, 1L,
                new AuthoringContextService.DraftInput("draft-x", "SQL", CONTENT, 5L, 6L), 3);

        assertThat(ctx.taskRef()).isEqualTo("draft-x");
        assertThat(ctx.reads()).extracting(AuthoringContext.TableFact::table).containsExactly("ods.user");
        assertThat(ctx.writes()).extracting(AuthoringContext.TableFact::table).containsExactly("dw.user_daily");
        // 无副作用：草稿路径绝不读任务库
        verify(taskDefs, never()).findById(any());
    }

    @Test
    void draftEqualsPushedForSameContent() {
        var resolver = resolverStub();
        var lineage = emptyLineage();

        // 已 push：taskDefs 返回同 content 的定义
        var taskDefs = mock(TaskDefRepository.class);
        TaskDef def = new TaskDef();
        def.setId(10L);
        def.setTenantId(1L);
        def.setProjectId(1L);
        def.setType("SQL");
        def.setContent(CONTENT);
        def.setDatasourceId(5L);
        def.setTargetDatasourceId(6L);
        when(taskDefs.findById(10L)).thenReturn(Optional.of(def));

        var svc = service(lineage, resolver, taskDefs);
        var pushed = svc.context(1L, 1L, "10", 3);
        var draft = svc.contextForDraft(1L, 1L,
                new AuthoringContextService.DraftInput("10", "SQL", CONTENT, 5L, 6L), 3);

        assertThat(draft.reads()).extracting(AuthoringContext.TableFact::table)
                .isEqualTo(pushed.reads().stream().map(AuthoringContext.TableFact::table).toList());
        assertThat(draft.writes()).extracting(AuthoringContext.TableFact::table)
                .isEqualTo(pushed.writes().stream().map(AuthoringContext.TableFact::table).toList());
    }

    @Test
    void emptyDraftContentYieldsPartialNotCrash() {
        var svc = service(emptyLineage(), mock(TaskLineageResolver.class), mock(TaskDefRepository.class));
        var ctx = svc.contextForDraft(1L, 1L,
                new AuthoringContextService.DraftInput("d", "SQL", null, 5L, 6L), 3);
        assertThat(ctx.reads()).isEmpty();
        assertThat(ctx.partial()).extracting(AuthoringContext.MissingNote::source).contains("draft");
    }
}
