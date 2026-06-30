package com.dataweave.alert.application;

import com.dataweave.alert.domain.AlertEvent;
import com.dataweave.alert.domain.AlertRule;
import com.dataweave.alert.domain.repository.AlertRuleRepository;
import com.dataweave.master.application.MetricService;
import com.dataweave.master.domain.AtomicMetric;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 026 US3：全租户 POLL 覆盖——evaluate() 遍历所有租户的启用 POLL 规则。
 */
class MetricPollMultiTenantTest {

    private AlertRuleRepository ruleRepo;
    private AlertLifecycleService lifecycle;
    private AlertDispatchService dispatch;
    private MetricService metricService;
    private MetricPollEvaluator poll;

    @BeforeEach
    void setUp() {
        ruleRepo = mock(AlertRuleRepository.class);
        lifecycle = mock(AlertLifecycleService.class);
        dispatch = mock(AlertDispatchService.class);
        metricService = mock(MetricService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AlertEvaluator evaluator = new AlertEvaluator();
        poll = new MetricPollEvaluator(ruleRepo, evaluator, lifecycle, dispatch, metricService, jdbc);
        when(lifecycle.onSignal(any(AlertRule.class), any(AlertEvent.class)))
                .thenAnswer(inv -> Optional.of(inv.getArgument(1)));
    }

    private AlertRule pollRule(long id, long tenantId) {
        AlertRule r = new AlertRule();
        r.setId(id);
        r.setTenantId(tenantId);
        r.setEnabled(1);
        r.setEvalMode("POLL");
        r.setSeverity("HIGH");
        r.setEvalIntervalSec(30);
        r.setConditionJson("{\"metric_key\":\"task.fail_rate\",\"comparator\":\"GT\",\"threshold\":5}");
        return r;
    }

    @Test
    void evaluates_poll_rules_across_all_tenants() {
        AlertRule ruleA = pollRule(1L, 100L);
        AlertRule ruleB = pollRule(2L, 200L);
        // 关键：跨租户查询返回两个租户的规则
        when(ruleRepo.findByEvalModeAndEnabled("POLL", 1)).thenReturn(List.of(ruleA, ruleB));
        when(metricService.findLatestByCode(anyString())).thenReturn(Optional.of(new AtomicMetric()));
        when(metricService.evaluate(any(AtomicMetric.class))).thenReturn(9.0);

        poll.evaluate();

        ArgumentCaptor<AlertEvent> captor = ArgumentCaptor.forClass(AlertEvent.class);
        verify(dispatch, times(2)).dispatch(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(AlertEvent::getTenantId)
                .containsExactlyInAnyOrder(100L, 200L);
        // 不再硬编码 tenant 1
        verify(ruleRepo, never()).findByTenantIdAndEvalModeAndEnabled(eq(1L), anyString(), any());
    }
}
