package com.dataweave.master.application;

import com.dataweave.master.domain.Finding;
import com.dataweave.master.domain.FindingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通用发现服务：去重落库 + 状态流转（OPEN→ANNOUNCED→RESOLVED）。
 */
@ExtendWith(MockitoExtension.class)
class FindingServiceTest {

    @Mock
    private FindingRepository repository;

    private FindingService service;

    @BeforeEach
    void setUp() {
        service = new FindingService(repository);
    }

    private Finding taskFailure(String targetId) {
        Finding f = new Finding();
        f.setSource("TASK_FAILURE");
        f.setTargetType("TASK_INSTANCE");
        f.setTargetId(targetId);
        f.setTitle("订单宽表加工 失败 · OOM");
        f.setRootCause("node-3 内存 95%");
        return f;
    }

    @Test
    void recordIfNew_persistsWithDefaults_whenNoActiveDuplicate() {
        when(repository.findFirstBySourceAndTargetTypeAndTargetIdAndStatusInOrderByIdDesc(
                eq("TASK_FAILURE"), eq("TASK_INSTANCE"), eq("inst-1"), anyList()))
                .thenReturn(Optional.empty());
        when(repository.save(any(Finding.class))).thenAnswer(inv -> inv.getArgument(0));

        Finding saved = service.recordIfNew(taskFailure("inst-1")).orElseThrow();

        ArgumentCaptor<Finding> cap = ArgumentCaptor.forClass(Finding.class);
        verify(repository).save(cap.capture());
        Finding f = cap.getValue();
        assertThat(f.getStatus()).isEqualTo("OPEN");
        assertThat(f.getAnnounced()).isZero();
        assertThat(f.getSeverity()).isEqualTo("WARN");
        assertThat(f.getTenantId()).isEqualTo(1L);
        assertThat(f.getProjectId()).isEqualTo(1L);
        assertThat(f.getCreatedAt()).isNotNull();
        assertThat(saved).isSameAs(f);
    }

    @Test
    void recordIfNew_returnsExisting_andDoesNotSave_whenActiveDuplicate() {
        Finding existing = taskFailure("inst-1");
        existing.setId(7L);
        existing.setStatus("OPEN");
        when(repository.findFirstBySourceAndTargetTypeAndTargetIdAndStatusInOrderByIdDesc(
                eq("TASK_FAILURE"), eq("TASK_INSTANCE"), eq("inst-1"), anyList()))
                .thenReturn(Optional.of(existing));

        Optional<Finding> result = service.recordIfNew(taskFailure("inst-1"));

        assertThat(result).isEmpty();
        verify(repository, never()).save(any());
    }

    @Test
    void resolve_transitionsToResolved() {
        Finding f = taskFailure("inst-1");
        f.setId(3L);
        f.setStatus("OPEN");
        when(repository.findById(3L)).thenReturn(Optional.of(f));
        when(repository.save(any(Finding.class))).thenAnswer(inv -> inv.getArgument(0));

        Finding resolved = service.resolve(3L).orElseThrow();

        assertThat(resolved.getStatus()).isEqualTo("RESOLVED");
    }

    @Test
    void markAnnounced_setsFlag_andPromotesOpenToAnnounced() {
        Finding f = taskFailure("inst-1");
        f.setId(4L);
        f.setStatus("OPEN");
        f.setAnnounced(0);
        when(repository.findById(4L)).thenReturn(Optional.of(f));
        when(repository.save(any(Finding.class))).thenAnswer(inv -> inv.getArgument(0));

        Finding announced = service.markAnnounced(4L).orElseThrow();

        assertThat(announced.getAnnounced()).isEqualTo(1);
        assertThat(announced.getStatus()).isEqualTo("ANNOUNCED");
    }
}
