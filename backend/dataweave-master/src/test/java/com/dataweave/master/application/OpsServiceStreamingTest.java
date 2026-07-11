package com.dataweave.master.application;

import com.dataweave.master.domain.EventBus;
import com.dataweave.master.domain.LogBus;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.TaskInstanceRepository;
import com.dataweave.master.domain.WorkflowInstanceRepository;
import com.dataweave.master.i18n.BizException;
import com.dataweave.master.infrastructure.CheckpointRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 062 US3 优雅停止（stopWithSavepoint）领域逻辑单测（Mockito，无 DB / 无真 Flink）。
 * 覆盖：成功（触发 savepoint→写检查点→CAS STOPPED）/ savepoint 不可用 / 非 long_running / 非 RUNNING / 无句柄。
 */
class OpsServiceStreamingTest {

    private TaskInstanceRepository instanceRepository;
    private InstanceStateMachine stateMachine;
    private CheckpointService checkpointService;
    private FlinkSavepointClient flinkClient;
    private OpsService ops;

    private static final UUID IID = UUID.fromString("00000000-0000-7000-8000-000000000062");
    private static final String HANDLE = "{\"jobId\":\"job-1\",\"restEndpoint\":\"http://flink:8081\"}";

    @BeforeEach
    void setUp() {
        instanceRepository = mock(TaskInstanceRepository.class);
        stateMachine = mock(InstanceStateMachine.class);
        checkpointService = mock(CheckpointService.class);
        flinkClient = mock(FlinkSavepointClient.class);
        ops = new OpsService(
                mock(com.dataweave.master.domain.TaskDefRepository.class),
                instanceRepository,
                mock(WorkflowInstanceRepository.class),
                mock(com.dataweave.master.domain.WorkflowDefRepository.class),
                stateMachine,
                mock(WorkflowStateService.class),
                mock(LogBus.class),
                mock(EventBus.class),
                mock(JdbcTemplate.class),
                mock(com.dataweave.master.domain.AgentActionRepository.class),
                mock(CheckpointRepository.class),
                checkpointService,
                flinkClient,
                24L);
    }

    private TaskInstance instance(String state, boolean longRunning, String handle) {
        TaskInstance ti = new TaskInstance();
        ti.setId(IID);
        ti.setTaskId(1L);
        ti.setState(state);
        ti.setLongRunning(longRunning);
        ti.setExternalJobHandle(handle);
        return ti;
    }

    @Test
    void stopWithSavepoint_成功_触发savepoint写检查点并CAS_STOPPED() {
        when(instanceRepository.findById(IID)).thenReturn(Optional.of(instance("RUNNING", true, HANDLE)));
        when(flinkClient.stopWithSavepoint(eq("http://flink:8081"), eq("job-1"), isNull()))
                .thenReturn("hdfs:///sp/job-1-1");
        UUID cpId = UUID.randomUUID();
        when(checkpointService.recordSuccess(eq(IID), eq("hdfs:///sp/job-1-1"), eq("job-1"), isNull()))
                .thenReturn(cpId);
        when(stateMachine.casTaskTerminal(eq(IID), eq("RUNNING"), eq("STOPPED"), anyString())).thenReturn(true);

        var r = ops.stopWithSavepoint(IID, null);

        assertThat(r.state()).isEqualTo("STOPPED");
        assertThat(r.checkpointId()).isEqualTo(cpId);
        assertThat(r.checkpointPath()).isEqualTo("hdfs:///sp/job-1-1");
        verify(checkpointService).recordSuccess(eq(IID), eq("hdfs:///sp/job-1-1"), eq("job-1"), isNull());
        verify(stateMachine).casTaskTerminal(eq(IID), eq("RUNNING"), eq("STOPPED"), anyString());
    }

    @Test
    void stopWithSavepoint_savepoint不可用_不写检查点不CAS() {
        when(instanceRepository.findById(IID)).thenReturn(Optional.of(instance("RUNNING", true, HANDLE)));
        when(flinkClient.stopWithSavepoint(anyString(), anyString(), any()))
                .thenThrow(new FlinkSavepointClient.SavepointException("引擎不可达"));

        assertThatThrownBy(() -> ops.stopWithSavepoint(IID, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("savepoint.unavailable");
        verify(checkpointService, never()).recordSuccess(any(), anyString(), anyString(), any());
        verify(stateMachine, never()).casTaskTerminal(any(), anyString(), anyString(), anyString());
    }

    @Test
    void stopWithSavepoint_非long_running_拒绝() {
        when(instanceRepository.findById(IID)).thenReturn(Optional.of(instance("RUNNING", false, HANDLE)));
        assertThatThrownBy(() -> ops.stopWithSavepoint(IID, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("not_long_running");
    }

    @Test
    void stopWithSavepoint_非RUNNING_拒绝() {
        when(instanceRepository.findById(IID)).thenReturn(Optional.of(instance("STOPPED", true, HANDLE)));
        assertThatThrownBy(() -> ops.stopWithSavepoint(IID, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("not_resumable");
    }

    @Test
    void stopWithSavepoint_无句柄_savepoint不可用() {
        when(instanceRepository.findById(IID)).thenReturn(Optional.of(instance("RUNNING", true, null)));
        assertThatThrownBy(() -> ops.stopWithSavepoint(IID, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("savepoint.unavailable");
    }
}
