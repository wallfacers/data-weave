package com.dataweave.master.application;

import com.dataweave.master.domain.AgentAction;
import com.dataweave.master.domain.AgentActionRepository;
import com.dataweave.master.i18n.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审批单三终态：批准（执行）、拒绝、超时过期；L3 二次确认。
 */
@ExtendWith(MockitoExtension.class)
class ApprovalServiceTest {

    @Mock
    private AgentActionRepository actionRepository;
    @Mock
    private PlatformActionExecutor executor;

    private ApprovalService approvalService;

    @BeforeEach
    void setUp() {
        approvalService = new ApprovalService(actionRepository, executor);
        org.mockito.Mockito.lenient()
                .when(actionRepository.save(any(AgentAction.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private AgentAction pending(String level, String targetId) {
        AgentAction a = new AgentAction();
        a.setId(42L);
        a.setPolicyLevel(level);
        a.setApprovalStatus("PENDING");
        a.setActionType("TASK_RERUN");
        a.setTargetId(targetId);
        a.setExpiresAt(LocalDateTime.now().plusMinutes(30));
        return a;
    }

    @Test
    void approve_pendingL2_executesAndMarksApproved() {
        AgentAction a = pending("L2", "100");
        when(actionRepository.findById(42L)).thenReturn(Optional.of(a));
        when(executor.execute(a)).thenReturn(
                new PlatformActionExecutor.ExecOutcome(true, "已执行", "{}", java.util.UUID.fromString("01910000-0010-7000-8000-000000000088")));

        ApprovalService.ApprovalResult r = approvalService.approve(42L, "alice", null);

        assertThat(r.success()).isTrue();
        assertThat(r.resultInstanceId()).isEqualTo(java.util.UUID.fromString("01910000-0010-7000-8000-000000000088"));
        assertThat(a.getApprovalStatus()).isEqualTo("APPROVED");
        assertThat(a.getApprovedBy()).isEqualTo("alice");
        assertThat(a.getExecutedAt()).isNotNull();
        verify(executor).execute(a);
    }

    @Test
    void approve_alreadyApproved_fails() {
        AgentAction a = pending("L2", "100");
        a.setApprovalStatus("APPROVED");
        when(actionRepository.findById(42L)).thenReturn(Optional.of(a));

        assertThatThrownBy(() -> approvalService.approve(42L, "alice", null))
                .isInstanceOf(BizException.class)
                .hasMessage("approval.wrong_state");
        verify(executor, never()).execute(any());
    }

    @Test
    void approve_expired_marksExpiredAndFails() {
        AgentAction a = pending("L2", "100");
        a.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(actionRepository.findById(42L)).thenReturn(Optional.of(a));

        assertThatThrownBy(() -> approvalService.approve(42L, "alice", null))
                .isInstanceOf(BizException.class)
                .hasMessage("approval.expired");
        assertThat(a.getApprovalStatus()).isEqualTo("EXPIRED");
        verify(executor, never()).execute(any());
    }

    @Test
    void reject_pending_marksRejectedNoExecute() {
        AgentAction a = pending("L2", "100");
        when(actionRepository.findById(42L)).thenReturn(Optional.of(a));

        ApprovalService.ApprovalResult r = approvalService.reject(42L, "bob");
        assertThat(r.success()).isTrue();
        assertThat(a.getApprovalStatus()).isEqualTo("REJECTED");
        assertThat(a.getApprovedBy()).isEqualTo("bob");
        verify(executor, never()).execute(any());
    }

    @Test
    void approveL3_requiresMatchingConfirmation() {
        AgentAction a = pending("L3", "dwd_orders");
        when(actionRepository.findById(42L)).thenReturn(Optional.of(a));

        assertThatThrownBy(() -> approvalService.approve(42L, "alice", null))
                .isInstanceOf(BizException.class)
                .hasMessage("approval.l3_confirm_required");
        assertThatThrownBy(() -> approvalService.approve(42L, "alice", "wrong_name"))
                .isInstanceOf(BizException.class)
                .hasMessage("approval.l3_confirm_required");
        verify(executor, never()).execute(any());

        when(executor.execute(a)).thenReturn(
                new PlatformActionExecutor.ExecOutcome(true, "drop ok", "{}", null));
        ApprovalService.ApprovalResult ok = approvalService.approve(42L, "alice", "dwd_orders");
        assertThat(ok.success()).isTrue();
        assertThat(a.getApprovalStatus()).isEqualTo("APPROVED");
    }

    @Test
    void expireStale_marksPastDueExpired() {
        AgentAction fresh = pending("L2", "100");
        AgentAction stale = pending("L2", "200");
        stale.setExpiresAt(LocalDateTime.now().minusMinutes(5));
        when(actionRepository.findByApprovalStatusOrderByIdAsc("PENDING"))
                .thenReturn(List.of(fresh, stale));

        int n = approvalService.expireStale();
        assertThat(n).isEqualTo(1);
        assertThat(stale.getApprovalStatus()).isEqualTo("EXPIRED");
        assertThat(fresh.getApprovalStatus()).isEqualTo("PENDING");
    }
}
