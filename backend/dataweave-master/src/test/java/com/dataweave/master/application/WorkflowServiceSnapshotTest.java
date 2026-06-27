package com.dataweave.master.application;

import com.dataweave.master.domain.*;
import com.dataweave.master.i18n.BizException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D1 状态中立建快照内核 单元测试：writeWorkflowVersionSnapshot 不改 status，
 * publish() 重构后仍正确晋级 ONLINE + 守卫。
 */
class WorkflowServiceSnapshotTest {

    private final WorkflowDefRepository wfRepo = mock(WorkflowDefRepository.class);
    private final WorkflowDefVersionRepository wfVerRepo = mock(WorkflowDefVersionRepository.class);
    private final WorkflowNodeRepository nodeRepo = mock(WorkflowNodeRepository.class);
    private final WorkflowEdgeRepository edgeRepo = mock(WorkflowEdgeRepository.class);
    private final WorkflowDependencyRepository depRepo = mock(WorkflowDependencyRepository.class);
    private final TaskDefRepository taskDefRepo = mock(TaskDefRepository.class);
    private final TaskDefVersionRepository taskVerRepo = mock(TaskDefVersionRepository.class);
    private final WorkflowGraphValidator graphValidator = mock(WorkflowGraphValidator.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private WorkflowService newService() {
        when(wfRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(wfVerRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(nodeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(edgeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        return new WorkflowService(wfRepo, wfVerRepo, nodeRepo, edgeRepo, depRepo,
                taskDefRepo, taskVerRepo, graphValidator, null, objectMapper);
    }

    private WorkflowDef draftWf() {
        WorkflowDef wf = new WorkflowDef();
        wf.setId(1L);
        wf.setTenantId(1L);
        wf.setProjectId(10L);
        wf.setName("测试流");
        wf.setStatus("DRAFT");
        wf.setCurrentVersionNo(0);
        wf.setHasDraftChange(1);
        return wf;
    }

    // ── writeWorkflowVersionSnapshot 状态中立 ──

    @Test
    void writeSnapshot_createsVersionButDoesNotChangeStatus() {
        WorkflowService svc = newService();
        WorkflowDef wf = draftWf();
        when(wfRepo.findById(1L)).thenReturn(Optional.of(wf));
        when(nodeRepo.findByWorkflowIdAndDeleted(1L, 0)).thenReturn(List.of());
        when(edgeRepo.findByWorkflowIdAndDeleted(1L, 0)).thenReturn(List.of());

        Integer newVer = svc.writeWorkflowVersionSnapshot(1L, "测试快照");

        assertThat(newVer).isEqualTo(1);
        assertThat(wf.getCurrentVersionNo()).isEqualTo(1);
        // 状态不应被改为 ONLINE
        assertThat(wf.getStatus()).isEqualTo("DRAFT");
        // hasDraftChange 不改
        assertThat(wf.getHasDraftChange()).isEqualTo(1);
        // version row 被保存
        verify(wfVerRepo).save(any(WorkflowDefVersion.class));
    }

    @Test
    void writeSnapshot_updatesCurrentVersionNo() {
        WorkflowService svc = newService();
        WorkflowDef wf = draftWf();
        wf.setCurrentVersionNo(3);
        when(wfRepo.findById(1L)).thenReturn(Optional.of(wf));
        when(nodeRepo.findByWorkflowIdAndDeleted(1L, 0)).thenReturn(List.of());
        when(edgeRepo.findByWorkflowIdAndDeleted(1L, 0)).thenReturn(List.of());

        Integer newVer = svc.writeWorkflowVersionSnapshot(1L, "v4");

        assertThat(newVer).isEqualTo(4);
    }

    // ── publish() 零回归 ──

    @Test
    void publish_promotesToOnline() {
        WorkflowService svc = newService();
        WorkflowDef wf = draftWf();
        // 需要一个节点以通过空 DAG 守卫
        WorkflowNode node = new WorkflowNode();
        node.setId(10L);
        node.setNodeKey("n1");
        node.setNodeType("VIRTUAL");
        node.setName("start");

        when(wfRepo.findById(1L)).thenReturn(Optional.of(wf));
        when(nodeRepo.findByWorkflowIdAndDeleted(1L, 0)).thenReturn(List.of(node));
        when(edgeRepo.findByWorkflowIdAndDeleted(1L, 0)).thenReturn(List.of());

        WorkflowDef published = svc.publish(1L, "发布 v1");

        assertThat(published.getStatus()).isEqualTo("ONLINE");
        assertThat(published.getHasDraftChange()).isEqualTo(0);
        assertThat(published.getCurrentVersionNo()).isEqualTo(1);
    }

    @Test
    void publish_rejectsEmptyDag() {
        WorkflowService svc = newService();
        WorkflowDef wf = draftWf();
        when(wfRepo.findById(1L)).thenReturn(Optional.of(wf));
        when(nodeRepo.findByWorkflowIdAndDeleted(1L, 0)).thenReturn(List.of());

        assertThatThrownBy(() -> svc.publish(1L, "空"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("workflow.publish.empty");
    }
}
