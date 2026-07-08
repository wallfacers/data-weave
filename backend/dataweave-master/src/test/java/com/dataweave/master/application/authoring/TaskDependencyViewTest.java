package com.dataweave.master.application.authoring;

import java.util.LinkedHashMap;
import java.util.List;

import com.dataweave.master.application.LineageQueryService;
import com.dataweave.master.application.lineage.grounding.CatalogGroundingService;
import com.dataweave.master.application.lineage.script.ScriptLineageService;
import com.dataweave.master.domain.TaskDefRepository;
import com.dataweave.master.domain.WorkflowEdge;
import com.dataweave.master.domain.WorkflowEdgeRepository;
import com.dataweave.master.domain.WorkflowNode;
import com.dataweave.master.domain.WorkflowNodeRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 058 T009（US1）：任务依赖合并（FR-006）单测。
 * ① 纯合并逻辑——声明/推导同键升 BOTH 取更短跳距、单侧保留 origin；
 * ② 服务装配——从 {@code workflow_node/edge} 抽声明上/下游 + 与血缘推导合并。
 * 全程零 neo4j：推导通道以 Mockito 桩注入，佐证声明侧可脱离图库独立验证。
 */
class TaskDependencyViewTest {

    // ── ① 纯合并逻辑 ──────────────────────────────────────────────

    @Test
    void mergeMarksBothWhenDeclaredAndDerivedAgree() {
        var declaredUp = List.of(new DependencyEdge("20", "10", 1, DependencyEdge.DECLARED));
        var derivedUp = List.of(new DependencyEdge("20", "10", 3, DependencyEdge.DERIVED));

        var view = TaskDependencyView.merge("10", declaredUp, derivedUp, List.of(), List.of());

        assertThat(view.upstream()).hasSize(1);
        DependencyEdge merged = view.upstream().get(0);
        assertThat(merged.origin()).isEqualTo(DependencyEdge.BOTH);
        assertThat(merged.hop()).isEqualTo(1); // 取更短跳距
    }

    @Test
    void mergeKeepsDeclaredOnlyAndDerivedOnlySeparately() {
        var declaredDown = List.of(new DependencyEdge("10", "30", 1, DependencyEdge.DECLARED));
        var derivedDown = List.of(new DependencyEdge("10", "40", 2, DependencyEdge.DERIVED));

        var view = TaskDependencyView.merge("10", List.of(), List.of(), declaredDown, derivedDown);

        assertThat(view.downstream()).extracting(DependencyEdge::toTaskRef, DependencyEdge::origin)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("30", DependencyEdge.DECLARED),
                        org.assertj.core.groups.Tuple.tuple("40", DependencyEdge.DERIVED));
    }

    // ── ② 服务装配（声明侧脱离 neo4j 验证） ────────────────────────

    private WorkflowNode node(long id, long taskId, long wf) {
        WorkflowNode n = new WorkflowNode();
        n.setId(id);
        n.setTaskId(taskId);
        n.setWorkflowId(wf);
        n.setTenantId(1L);
        n.setProjectId(1L);
        return n;
    }

    private WorkflowEdge edge(long from, long to, long wf) {
        WorkflowEdge e = new WorkflowEdge();
        e.setFromNodeId(from);
        e.setToNodeId(to);
        e.setWorkflowId(wf);
        return e;
    }

    @Test
    void serviceExtractsDeclaredUpstreamAndDownstream() {
        var nodes = mock(WorkflowNodeRepository.class);
        var edges = mock(WorkflowEdgeRepository.class);
        var lineage = mock(LineageQueryService.class);

        WorkflowNode self = node(1L, 10L, 100L);   // 主任务 10
        WorkflowNode up = node(2L, 20L, 100L);      // 上游 20
        WorkflowNode down = node(3L, 30L, 100L);    // 下游 30
        when(nodes.findByTaskIdAndDeleted(10L, 0)).thenReturn(List.of(self));
        when(nodes.findByWorkflowIdAndDeleted(100L, 0)).thenReturn(List.of(self, up, down));
        when(edges.findByWorkflowIdAndDeleted(100L, 0)).thenReturn(List.of(
                edge(2L, 1L, 100L),   // 20 → 10
                edge(1L, 3L, 100L))); // 10 → 30
        when(lineage.upstreamTaskLevels(1L, 1L, 10L)).thenReturn(new LinkedHashMap<>());
        when(lineage.downstreamTaskLevels(1L, 1L, 10L)).thenReturn(new LinkedHashMap<>());

        var svc = new AuthoringContextService(lineage, mock(ScriptLineageService.class),
                mock(CatalogGroundingService.class),
                mock(com.dataweave.master.application.lineage.TaskLineageResolver.class),
                edges, nodes, mock(TaskDefRepository.class));

        var view = svc.taskDependencies(1L, 1L, 10L);

        assertThat(view.upstream()).extracting(DependencyEdge::fromTaskRef, DependencyEdge::origin)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("20", DependencyEdge.DECLARED));
        assertThat(view.downstream()).extracting(DependencyEdge::toTaskRef, DependencyEdge::origin)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("30", DependencyEdge.DECLARED));
    }

    @Test
    void serviceMergesDerivedLineageWithDeclared() {
        var nodes = mock(WorkflowNodeRepository.class);
        var edges = mock(WorkflowEdgeRepository.class);
        var lineage = mock(LineageQueryService.class);

        WorkflowNode self = node(1L, 10L, 100L);
        WorkflowNode down = node(3L, 30L, 100L);
        when(nodes.findByTaskIdAndDeleted(10L, 0)).thenReturn(List.of(self));
        when(nodes.findByWorkflowIdAndDeleted(100L, 0)).thenReturn(List.of(self, down));
        when(edges.findByWorkflowIdAndDeleted(100L, 0)).thenReturn(List.of(edge(1L, 3L, 100L))); // 声明 10→30

        var derivedDown = new LinkedHashMap<Long, Integer>();
        derivedDown.put(30L, 2); // 推导也见 10→30
        var derivedUp = new LinkedHashMap<Long, Integer>();
        derivedUp.put(40L, 1);   // 推导独有上游 40→10
        when(lineage.downstreamTaskLevels(1L, 1L, 10L)).thenReturn(derivedDown);
        when(lineage.upstreamTaskLevels(1L, 1L, 10L)).thenReturn(derivedUp);

        var svc = new AuthoringContextService(lineage, mock(ScriptLineageService.class),
                mock(CatalogGroundingService.class),
                mock(com.dataweave.master.application.lineage.TaskLineageResolver.class),
                edges, nodes, mock(TaskDefRepository.class));

        var view = svc.taskDependencies(1L, 1L, 10L);

        // 声明+推导同现 → BOTH
        assertThat(view.downstream()).singleElement()
                .satisfies(e -> {
                    assertThat(e.toTaskRef()).isEqualTo("30");
                    assertThat(e.origin()).isEqualTo(DependencyEdge.BOTH);
                });
        // 推导独有 → DERIVED
        assertThat(view.upstream()).extracting(DependencyEdge::fromTaskRef, DependencyEdge::origin)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("40", DependencyEdge.DERIVED));
    }
}
