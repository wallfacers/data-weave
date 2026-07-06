package com.dataweave.master.application;

import com.dataweave.master.domain.EventBus;
import com.dataweave.master.domain.TaskDefRepository;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.TaskInstanceRepository;
import com.dataweave.master.domain.WorkflowDef;
import com.dataweave.master.domain.WorkflowDefVersionRepository;
import com.dataweave.master.domain.WorkflowEdgeRepository;
import com.dataweave.master.domain.WorkflowInstance;
import com.dataweave.master.domain.WorkflowInstanceRepository;
import com.dataweave.master.domain.WorkflowNode;
import com.dataweave.master.domain.WorkflowNodeFreezeRepository;
import com.dataweave.master.domain.WorkflowNodeRepository;
import com.dataweave.master.i18n.Messages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 045 T011:WorkflowTriggerService.trigger 批量物化测试 —— taskInstance 走 saveAll(批量),不再循环 save。
 * 045 还验证 wake 推迟到 afterCommit（单测无事务上下文 → 直接 publish）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkflowTriggerServiceTest {

    @Mock WorkflowNodeRepository nodeRepository;
    @Mock WorkflowEdgeRepository edgeRepository;
    @Mock WorkflowInstanceRepository workflowInstanceRepository;
    @Mock TaskInstanceRepository taskInstanceRepository;
    @Mock TaskDefRepository taskDefRepository;
    @Mock WorkflowDefVersionRepository workflowDefVersionRepository;
    @Mock WorkflowNodeFreezeRepository nodeFreezeRepository;
    @Mock WorkflowStateService workflowStateService;
    @Mock EventBus eventBus;
    @Mock ObjectMapper objectMapper;

    private WorkflowTriggerService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowTriggerService(nodeRepository, edgeRepository,
                workflowInstanceRepository, taskInstanceRepository, taskDefRepository,
                workflowDefVersionRepository, nodeFreezeRepository, workflowStateService,
                eventBus, objectMapper,
                mock(com.dataweave.master.application.readiness.ReadinessInitializer.class),
                mock(org.springframework.jdbc.core.JdbcTemplate.class));
    }

    @Test
    void trigger_taskInstance批量saveAll_不循环save() {
        // given:1 个 VIRTUAL 节点（物化即 SUCCESS，无 pending，路径最简）
        WorkflowDef wf = mock(WorkflowDef.class);
        when(wf.getId()).thenReturn(1L);
        when(wf.getCurrentVersionNo()).thenReturn(null);  // 回退 live 物化
        when(wf.getTenantId()).thenReturn(1L);
        when(wf.getProjectId()).thenReturn(1L);
        when(wf.getName()).thenReturn("test-wf");
        when(wf.getCron()).thenReturn("*/10 * * * * *");
        when(wf.getPriority()).thenReturn(5);
        when(wf.getScheduleType()).thenReturn("CRON");

        WorkflowNode node = mock(WorkflowNode.class);
        when(node.getNodeKey()).thenReturn("n1");
        when(node.getId()).thenReturn(10L);
        when(node.getNodeType()).thenReturn("VIRTUAL");
        when(node.getTaskId()).thenReturn(null);
        when(nodeRepository.findByWorkflowIdAndDeleted(1L, 0)).thenReturn(List.of(node));
        when(edgeRepository.findByWorkflowIdAndDeleted(1L, 0)).thenReturn(List.of());
        when(nodeFreezeRepository.findDefinitionFrozen(1L)).thenReturn(List.of());

        UUID wiId = UUID.randomUUID();
        WorkflowInstance savedWi = mock(WorkflowInstance.class);
        when(savedWi.getId()).thenReturn(wiId);
        when(workflowInstanceRepository.save(any())).thenReturn(savedWi);

        // when
        UUID result = service.trigger(wf, "CRON", "2026-07-04", 5,
                Messages.DEFAULT_LOCALE, "FULL", null, "NORMAL", null, 0, null);

        // then:批量 saveAll（一次）,不循环 save
        assertThat(result).isEqualTo(wiId);
        verify(taskInstanceRepository).saveAll(anyList());
        verify(taskInstanceRepository, never()).save(any(TaskInstance.class));
        verify(workflowStateService).computeAndUpdate(wiId);  // 无 pending → 聚合校正
        verify(eventBus).publish(any(), any());  // wake（无事务上下文，直接 publish）
    }
}
