package com.dataweave.master.application;

import com.dataweave.master.domain.AgentAction;
import com.dataweave.master.domain.AgentActionRepository;
import com.dataweave.master.i18n.Messages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 闸门三路出口：L0/L1 直执行、L2/L3 建审批单、L4 拒绝；agent_action 留痕。
 */
@ExtendWith(MockitoExtension.class)
class GatedActionServiceTest {

    @Mock
    private PolicyEngine policyEngine;
    @Mock
    private PlatformActionExecutor executor;
    @Mock
    private AgentActionRepository actionRepository;

    private GatedActionService gate;

    private Messages realMessages() {
        ReloadableResourceBundleMessageSource ms = new ReloadableResourceBundleMessageSource();
        ms.setBasename("classpath:messages");
        ms.setDefaultEncoding("UTF-8");
        ms.setFallbackToSystemLocale(false);
        return new Messages(ms);
    }

    @BeforeEach
    void setUp() {
        gate = new GatedActionService(policyEngine, executor, actionRepository, realMessages(), 30L);
        // save 回填自增 id
        when(actionRepository.save(any(AgentAction.class))).thenAnswer(inv -> {
            AgentAction a = inv.getArgument(0);
            if (a.getId() == null) {
                a.setId(1L);
            }
            return a;
        });
    }

    private PolicyDecision decision(PolicyLevel level, boolean confirm) {
        return new PolicyDecision(level, PolicyDecision.outcomeFor(level), confirm,
                false, List.of("test"));
    }

    @Test
    void l1_executesAndRecords() {
        when(policyEngine.decide(any(), any())).thenReturn(decision(PolicyLevel.L1, false));
        when(executor.execute(any(), any())).thenReturn(
                new PlatformActionExecutor.ExecOutcome(true, "重跑成功", "{}", java.util.UUID.fromString("01910000-0010-7000-8000-000000000077")));

        GateResult r = gate.submit(ActionRequest.builder()
                .toolName("task_rerun").actionType("TASK_RERUN")
                .targetType("TASK_INSTANCE").targetId("100").actor("agent").build());

        assertThat(r.executed()).isTrue();
        assertThat(r.message()).isEqualTo("重跑成功");
        assertThat(r.resultInstanceId()).isEqualTo(java.util.UUID.fromString("01910000-0010-7000-8000-000000000077"));
        verify(executor).execute(any(), any());

        ArgumentCaptor<AgentAction> cap = ArgumentCaptor.forClass(AgentAction.class);
        verify(actionRepository, org.mockito.Mockito.atLeastOnce()).save(cap.capture());
        AgentAction saved = cap.getValue();
        assertThat(saved.getApprovalStatus()).isEqualTo("NONE");
        assertThat(saved.getExecutedAt()).isNotNull();
        assertThat(saved.getPolicyLevel()).isEqualTo("L1");
    }

    @Test
    void l2_createsApprovalDoesNotExecute() {
        when(policyEngine.decide(any(), any())).thenReturn(decision(PolicyLevel.L2, false));

        GateResult r = gate.submit(ActionRequest.builder()
                .toolName("task_rerun").actionType("TASK_RERUN")
                .targetId("100").summary("重跑非本平台 app").build());

        assertThat(r.pending()).isTrue();
        assertThat(r.actionId()).isNotNull();
        assertThat(r.summary()).isEqualTo("重跑非本平台 app");
        verify(executor, never()).execute(any());

        ArgumentCaptor<AgentAction> cap = ArgumentCaptor.forClass(AgentAction.class);
        verify(actionRepository).save(cap.capture());
        assertThat(cap.getValue().getApprovalStatus()).isEqualTo("PENDING");
        assertThat(cap.getValue().getExpiresAt()).isNotNull();
    }

    @Test
    void l3_pendingWithConfirmationFlag() {
        when(policyEngine.decide(any(), any())).thenReturn(decision(PolicyLevel.L3, true));
        GateResult r = gate.submit(ActionRequest.builder()
                .toolName("drop_table").actionType("DROP_TABLE").targetId("dwd_orders").build());
        assertThat(r.pending()).isTrue();
        assertThat(r.requiresConfirmation()).isTrue();
        verify(executor, never()).execute(any());
    }

    @Test
    void l4_rejectedDoesNotExecute() {
        when(policyEngine.decide(any(), any())).thenReturn(decision(PolicyLevel.L4, false));
        GateResult r = gate.submit(ActionRequest.builder()
                .command("rm -rf /data").actionType("NODE_EXEC").build());
        assertThat(r.rejected()).isTrue();
        verify(executor, never()).execute(any());

        ArgumentCaptor<AgentAction> cap = ArgumentCaptor.forClass(AgentAction.class);
        verify(actionRepository).save(cap.capture());
        assertThat(cap.getValue().getResultJson()).contains("rejected");
    }
}
