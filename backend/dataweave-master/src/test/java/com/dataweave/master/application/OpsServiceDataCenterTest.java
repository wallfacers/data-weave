package com.dataweave.master.application;

import com.dataweave.master.domain.EventBus;
import com.dataweave.master.domain.InstanceStates;
import com.dataweave.master.domain.LogBus;
import com.dataweave.master.domain.TaskDef;
import com.dataweave.master.domain.TaskDefRepository;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.TaskInstanceRepository;
import com.dataweave.master.domain.WorkflowInstanceRepository;
import com.dataweave.master.i18n.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * data-ops-center Stream A：置成功 / 重跑 / 冻结 的领域决策逻辑单测（Mockito，无 DB）。
 * SQL 密集的 queryInstances/backfill 由 API 模块 {@code OpsDataCenterEndpointTest}（真 H2+schema）端到端覆盖。
 */
class OpsServiceDataCenterTest {

    private TaskInstanceRepository instanceRepository;
    private TaskDefRepository taskDefRepository;
    private InstanceStateMachine stateMachine;
    private EventBus eventBus;
    private JdbcTemplate jdbc;
    private OpsService ops;

    @BeforeEach
    void setUp() {
        instanceRepository = mock(TaskInstanceRepository.class);
        taskDefRepository = mock(TaskDefRepository.class);
        WorkflowInstanceRepository workflowInstanceRepository = mock(WorkflowInstanceRepository.class);
        com.dataweave.master.domain.WorkflowDefRepository workflowDefRepository =
                mock(com.dataweave.master.domain.WorkflowDefRepository.class);
        stateMachine = mock(InstanceStateMachine.class);
        LogBus logBus = mock(LogBus.class);
        eventBus = mock(EventBus.class);
        jdbc = mock(JdbcTemplate.class);
        ops = new OpsService(taskDefRepository, instanceRepository, workflowInstanceRepository,
                workflowDefRepository, stateMachine, logBus, eventBus, jdbc);
    }

    private TaskInstance instance(String state) {
        TaskInstance ti = new TaskInstance();
        ti.setId(UUID.randomUUID());
        ti.setTaskId(1L);
        ti.setState(state);
        return ti;
    }

    // ─── 置成功 ─────────────────────────────────────────

    @Test
    void setSuccessFromFailedCasesAndWakes() {
        TaskInstance ti = instance(InstanceStates.FAILED);
        when(instanceRepository.findById(ti.getId())).thenReturn(Optional.of(ti));
        when(stateMachine.casTaskTerminal(eq(ti.getId()), eq(InstanceStates.FAILED),
                eq(InstanceStates.SUCCESS), any())).thenReturn(true);

        ops.setSuccess(ti.getId());

        verify(stateMachine).casTaskTerminal(ti.getId(), InstanceStates.FAILED, InstanceStates.SUCCESS, null);
        verify(eventBus).publish(eq(InstanceStates.WAKE_CHANNEL), anyString());
    }

    @Test
    void setSuccessFromRunningAllowed() {
        TaskInstance ti = instance(InstanceStates.RUNNING);
        when(instanceRepository.findById(ti.getId())).thenReturn(Optional.of(ti));
        when(stateMachine.casTaskTerminal(any(), any(), any(), any())).thenReturn(true);

        ops.setSuccess(ti.getId());

        verify(stateMachine).casTaskTerminal(ti.getId(), InstanceStates.RUNNING, InstanceStates.SUCCESS, null);
    }

    @Test
    void setSuccessFromWaitingRejected() {
        TaskInstance ti = instance(InstanceStates.WAITING);
        when(instanceRepository.findById(ti.getId())).thenReturn(Optional.of(ti));

        assertThatThrownBy(() -> ops.setSuccess(ti.getId()))
                .isInstanceOf(BizException.class);
        verify(stateMachine, never()).casTaskTerminal(any(), any(), any(), any());
        verify(eventBus, never()).publish(any(), any());
    }

    @Test
    void setSuccessFromNotRunRejected() {
        TaskInstance ti = instance(InstanceStates.NOT_RUN);
        when(instanceRepository.findById(ti.getId())).thenReturn(Optional.of(ti));

        assertThatThrownBy(() -> ops.setSuccess(ti.getId()))
                .isInstanceOf(BizException.class);
        verify(stateMachine, never()).casTaskTerminal(any(), any(), any(), any());
    }

    // ─── 重跑 ───────────────────────────────────────────

    @Test
    void rerunTerminalResetsToWaitingAndWakes() {
        TaskInstance ti = instance(InstanceStates.FAILED);
        when(instanceRepository.findById(ti.getId())).thenReturn(Optional.of(ti));

        ops.rerunInstance(ti.getId());

        verify(jdbc).update(anyString(), any(), eq(ti.getId()));
        verify(eventBus).publish(eq(InstanceStates.WAKE_CHANNEL), anyString());
    }

    @Test
    void rerunNonTerminalRejected() {
        TaskInstance ti = instance(InstanceStates.RUNNING);
        when(instanceRepository.findById(ti.getId())).thenReturn(Optional.of(ti));

        assertThatThrownBy(() -> ops.rerunInstance(ti.getId()))
                .isInstanceOf(BizException.class);
        verify(eventBus, never()).publish(any(), any());
    }

    // ─── 冻结 ───────────────────────────────────────────

    @Test
    void setFrozenTrueWritesOne() {
        TaskDef def = new TaskDef();
        def.setId(1L);
        when(taskDefRepository.findById(1L)).thenReturn(Optional.of(def));
        when(taskDefRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TaskDef out = ops.setFrozen(1L, true);

        assertThat(out.getFrozen()).isEqualTo(1);
    }

    @Test
    void setFrozenFalseWritesZero() {
        TaskDef def = new TaskDef();
        def.setId(1L);
        def.setFrozen(1);
        when(taskDefRepository.findById(1L)).thenReturn(Optional.of(def));
        when(taskDefRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TaskDef out = ops.setFrozen(1L, false);

        assertThat(out.getFrozen()).isEqualTo(0);
    }
}
