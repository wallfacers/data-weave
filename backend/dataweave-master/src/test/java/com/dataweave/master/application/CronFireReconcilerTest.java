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
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 045 T016:CronFireReconciler 单元测试 —— 幂等跳过 / DEAD 标记 / 空转。
 */
@ExtendWith(MockitoExtension.class)
class CronFireReconcilerTest {

    @Mock CronFireRepository cronFireRepository;
    @Mock WorkflowDefRepository workflowDefRepository;
    @Mock WorkflowInstanceRepository workflowInstanceRepository;
    @Mock WorkflowTriggerService triggerService;
    @Mock SchedulerClock clock;
    @Mock SchedulerMetrics metrics;
    @Mock JdbcTemplate jdbc;

    private CronFireReconciler reconciler;

    @BeforeEach
    void setUp() {
        reconciler = new CronFireReconciler(cronFireRepository, workflowDefRepository,
                workflowInstanceRepository, triggerService, clock, metrics, jdbc,
                30000L, 180000L);
    }

    @SuppressWarnings("unchecked")
    @Test
    void reconcile_无stale记录_空转不触发补偿() {
        when(clock.now()).thenReturn(LocalDateTime.now());
        when(jdbc.queryForList(anyString(), eq(Long.class), any(), anyInt())).thenReturn(List.<Long>of());

        reconciler.reconcile();

        verifyNoInteractions(triggerService);
        verify(metrics, never()).markReconcileReplayed();
    }

    @SuppressWarnings("unchecked")
    @Test
    void reconcile_已有instance_幂等跳过标FIRED不重创() {
        LocalDateTime now = LocalDateTime.now();
        Long cfId = 10L;
        Long wfId = 1L;
        LocalDateTime due = now.minusSeconds(60);
        when(clock.now()).thenReturn(now);
        when(jdbc.queryForList(anyString(), eq(Long.class), any(), anyInt())).thenReturn(List.<Long>of(cfId));

        CronFire guard = new CronFire(wfId, due);
        guard.setId(cfId);
        guard.setCreatedAt(due);
        when(cronFireRepository.findById(cfId)).thenReturn(Optional.of(guard));

        WorkflowDef wf = new WorkflowDef();
        wf.setId(wfId);
        wf.setStatus("ONLINE");
        wf.setDeleted(0);
        when(workflowDefRepository.findById(wfId)).thenReturn(Optional.of(wf));

        UUID existingInstanceId = UUID.randomUUID();
        WorkflowInstance existing = new WorkflowInstance();
        existing.setId(existingInstanceId);
        when(workflowInstanceRepository.findByWorkflowIdAndScheduledFireTime(wfId, due))
                .thenReturn(Optional.of(existing));

        reconciler.reconcile();

        verify(triggerService, never()).trigger(any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any());
        verify(metrics).markReconcileSkipped();
        verify(cronFireRepository).save(argThat(g -> "FIRED".equals(g.getStatus())
                && existingInstanceId.equals(g.getWorkflowInstanceId())));
    }

    @SuppressWarnings("unchecked")
    @Test
    void reconcile_超timeout_标DEAD并告警() {
        LocalDateTime now = LocalDateTime.now();
        Long cfId = 10L;
        Long wfId = 1L;
        // created 200s 前(超 timeout=180s)
        LocalDateTime staleCreated = now.minusSeconds(200);
        when(clock.now()).thenReturn(now);
        when(jdbc.queryForList(anyString(), eq(Long.class), any(), anyInt())).thenReturn(List.<Long>of(cfId));

        CronFire guard = new CronFire(wfId, now.minusSeconds(200));
        guard.setId(cfId);
        guard.setCreatedAt(staleCreated);
        when(cronFireRepository.findById(cfId)).thenReturn(Optional.of(guard));

        reconciler.reconcile();

        verify(metrics).markReconcileDead();
        verify(cronFireRepository).save(argThat(g -> "DEAD".equals(g.getStatus())));
        verify(triggerService, never()).trigger(any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any());
    }
}
