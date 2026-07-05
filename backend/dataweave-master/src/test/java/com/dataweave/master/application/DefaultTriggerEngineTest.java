package com.dataweave.master.application;

import com.dataweave.master.domain.CronFire;
import com.dataweave.master.domain.CronFireRepository;
import com.dataweave.master.domain.WorkflowDef;
import com.dataweave.master.domain.WorkflowDefRepository;
import com.dataweave.master.domain.WorkflowInstance;
import com.dataweave.master.domain.WorkflowInstanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * 045 T010:DefaultTriggerEngine.fireExecute 单元测试 —— 幂等跳过 / 正常物化 / cron_fire 回填 FIRED。
 * (fireArm 的 INSERT cron_fire 去重 + 队列入队由 T012 真跑集成验证。)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultTriggerEngineTest {

    @Mock WorkflowDefRepository workflowDefRepository;
    @Mock CronFireRepository cronFireRepository;
    @Mock WorkflowTriggerService triggerService;
    @Mock WorkflowInstanceRepository workflowInstanceRepository;
    @Mock SchedulerClock clock;
    @Mock SchedulerMetrics metrics;
    @Mock MasterRegistry masterRegistry;

    private DefaultTriggerEngine engine;

    @BeforeEach
    void setUp() {
        engine = new DefaultTriggerEngine(
                workflowDefRepository, cronFireRepository, triggerService, workflowInstanceRepository,
                clock, metrics, List.of(), masterRegistry,
                30000L, "fire_once", false,
                2, 4, 8);  // 测试小池:timer=2 worker=4 queue=8
    }

    @Test
    void fireExecute_已有instance_幂等跳过不重复trigger() {
        Long wfId = 1L;
        LocalDateTime due = LocalDateTime.now();
        Long cronFireId = 100L;

        WorkflowDef wf = mock(WorkflowDef.class);
        when(wf.getId()).thenReturn(wfId);
        when(workflowDefRepository.findById(wfId)).thenReturn(Optional.of(wf));
        when(clock.now()).thenReturn(due);

        UUID existingId = UUID.randomUUID();
        WorkflowInstance existing = mock(WorkflowInstance.class);
        when(existing.getId()).thenReturn(existingId);
        when(workflowInstanceRepository.findByWorkflowIdAndScheduledFireTime(wfId, due))
                .thenReturn(Optional.of(existing));

        CronFire guard = mock(CronFire.class);
        when(cronFireRepository.findById(cronFireId)).thenReturn(Optional.of(guard));

        engine.fireExecute(new DefaultTriggerEngine.FireTask(wfId, due, cronFireId));

        verify(triggerService, never()).trigger(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), anyInt(), any());
        verify(metrics).markReconcileSkipped();
        verify(cronFireRepository).save(any(CronFire.class));  // 回填 FIRED
    }

    @Test
    void fireExecute_无existing_委托trigger物化并回填FIRED() {
        Long wfId = 1L;
        LocalDateTime due = LocalDateTime.now();
        Long cronFireId = 100L;

        WorkflowDef wf = mock(WorkflowDef.class);
        when(wf.getId()).thenReturn(wfId);
        when(workflowDefRepository.findById(wfId)).thenReturn(Optional.of(wf));
        when(clock.now()).thenReturn(due);
        when(workflowInstanceRepository.findByWorkflowIdAndScheduledFireTime(wfId, due))
                .thenReturn(Optional.empty());

        UUID newInstanceId = UUID.randomUUID();
        when(triggerService.trigger(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), anyInt(), any())).thenReturn(newInstanceId);

        CronFire guard = mock(CronFire.class);
        when(cronFireRepository.findById(cronFireId)).thenReturn(Optional.of(guard));

        engine.fireExecute(new DefaultTriggerEngine.FireTask(wfId, due, cronFireId));

        verify(triggerService).trigger(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), anyInt(), any());
        verify(cronFireRepository).save(any(CronFire.class));  // 回填 FIRED + instance_id
        verify(metrics, never()).markReconcileSkipped();
    }

    @Test
    void fireExecute_wf已删_直接返回不物化() {
        Long wfId = 1L;
        LocalDateTime due = LocalDateTime.now();
        when(workflowDefRepository.findById(wfId)).thenReturn(Optional.empty());

        engine.fireExecute(new DefaultTriggerEngine.FireTask(wfId, due, 100L));

        verifyNoInteractions(triggerService);
        verify(cronFireRepository, never()).save(any());
    }
}
