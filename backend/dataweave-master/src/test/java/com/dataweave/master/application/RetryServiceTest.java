package com.dataweave.master.application;

import com.dataweave.master.domain.InstanceStates;
import com.dataweave.master.domain.TaskDef;
import com.dataweave.master.domain.TaskDefRepository;
import com.dataweave.master.domain.TaskInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetryServiceTest {

    private InstanceStateMachine stateMachine;
    private TaskDefRepository taskDefRepository;
    private RetryService retryService;

    @BeforeEach
    void setUp() {
        stateMachine = mock(InstanceStateMachine.class);
        taskDefRepository = mock(TaskDefRepository.class);
        retryService = new RetryService(stateMachine, taskDefRepository);
        when(stateMachine.casRequeue(any(), any())).thenReturn(true);
    }

    /** 060：businessAttempt = reportFailed 在 started_at≠null 时自增后的值（调用 scheduleRetry 前已回填 ti）。 */
    private TaskInstance failed(int businessAttempt) {
        TaskInstance ti = new TaskInstance();
        ti.setId(UUID.randomUUID());
        ti.setTaskId(1L);
        ti.setState(InstanceStates.RUNNING);
        ti.setBusinessAttempt(businessAttempt);
        return ti;
    }

    private void retryMax(int max) {
        TaskDef t = new TaskDef();
        t.setRetryMax(max);
        when(taskDefRepository.findById(1L)).thenReturn(Optional.of(t));
    }

    @Test
    void retriesWhileBusinessAttemptsRemain() {
        // 060：business_attempt <= retry_max 仍可重试（infra 回收不烧此计数，故不计入）
        retryMax(2);
        assertThat(retryService.scheduleRetry(failed(1))).isTrue();
        assertThat(retryService.scheduleRetry(failed(2))).isTrue();
    }

    @Test
    void stopsWhenBusinessRetriesExhausted() {
        retryMax(2);
        assertThat(retryService.scheduleRetry(failed(3))).isFalse();  // 3 > 2 → 终态 FAILED
        verify(stateMachine, never()).casRequeue(any(), eq(InstanceStates.RUNNING));
    }

    @Test
    void noRetryWhenRetryMaxZero() {
        retryMax(0);
        assertThat(retryService.scheduleRetry(failed(1))).isFalse();  // 首次业务失败（businessAttempt=1）即终态
    }
}
