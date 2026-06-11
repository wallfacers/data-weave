package com.dataweave.master.application;

import com.dataweave.master.domain.WorkflowDependency;
import com.dataweave.master.domain.WorkflowDependencyRepository;
import com.dataweave.master.domain.WorkflowEdge;
import com.dataweave.master.domain.WorkflowEdgeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowGraphValidatorTest {

    private WorkflowEdgeRepository edgeRepository;
    private WorkflowDependencyRepository dependencyRepository;
    private WorkflowGraphValidator validator;

    @BeforeEach
    void setUp() {
        edgeRepository = mock(WorkflowEdgeRepository.class);
        dependencyRepository = mock(WorkflowDependencyRepository.class);
        validator = new WorkflowGraphValidator(edgeRepository, dependencyRepository);
    }

    private WorkflowEdge edge(long from, long to) {
        WorkflowEdge e = new WorkflowEdge();
        e.setFromNodeId(from);
        e.setToNodeId(to);
        return e;
    }

    private WorkflowDependency dep(long workflowId, long dependWorkflowId) {
        WorkflowDependency d = new WorkflowDependency();
        d.setWorkflowId(workflowId);
        d.setDependWorkflowId(dependWorkflowId);
        d.setEnabled(1);
        return d;
    }

    @Test
    void acyclicWorkflowDag_passes() {
        when(edgeRepository.findByWorkflowId(1L)).thenReturn(List.of(edge(1, 2), edge(1, 3), edge(2, 4), edge(3, 4)));
        assertThatCode(() -> validator.validateWorkflowDagAcyclic(1L)).doesNotThrowAnyException();
    }

    @Test
    void cyclicWorkflowDag_rejected() {
        when(edgeRepository.findByWorkflowId(1L)).thenReturn(List.of(edge(1, 2), edge(2, 3), edge(3, 1)));
        assertThatThrownBy(() -> validator.validateWorkflowDagAcyclic(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("环");
    }

    @Test
    void selfDependency_rejected() {
        assertThatThrownBy(() -> validator.validateDependencyAcyclic(5L, 5L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("自身");
    }

    @Test
    void dependencyNotFormingCycle_passes() {
        // 现有：B(2) 依赖 A(1)。新增 C(3) 依赖 B(2) 不成环。
        when(dependencyRepository.findAll()).thenReturn(List.of(dep(2, 1)));
        assertThatCode(() -> validator.validateDependencyAcyclic(3L, 2L)).doesNotThrowAnyException();
    }

    @Test
    void dependencyFormingGlobalCycle_rejected() {
        // 现有：B(2) 依赖 A(1)，C(3) 依赖 B(2)。新增 A(1) 依赖 C(3) → 1→3→2→1 成环。
        when(dependencyRepository.findAll()).thenReturn(List.of(dep(2, 1), dep(3, 2)));
        assertThatThrownBy(() -> validator.validateDependencyAcyclic(1L, 3L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("成环");
    }
}
