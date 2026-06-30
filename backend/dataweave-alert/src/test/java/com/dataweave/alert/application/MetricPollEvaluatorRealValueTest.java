package com.dataweave.alert.application;

import com.dataweave.alert.domain.AlertEvent;
import com.dataweave.alert.domain.AlertRule;
import com.dataweave.alert.domain.repository.AlertRuleRepository;
import com.dataweave.master.application.MetricService;
import com.dataweave.master.domain.AtomicMetric;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 026 US1：MetricPollEvaluator 读真实指标值、越阈触发、缺值跳过。
 */
class MetricPollEvaluatorRealValueTest {

    private AlertRuleRepository ruleRepo;
    private AlertLifecycleService lifecycle;
    private AlertDispatchService dispatch;
    private MetricService metricService;
    private JdbcTemplate jdbc;
    private MetricPollEvaluator poll;

    @BeforeEach
    void setUp() {
        ruleRepo = mock(AlertRuleRepository.class);
        lifecycle = mock(AlertLifecycleService.class);
        dispatch = mock(AlertDispatchService.class);
        metricService = mock(MetricService.class);
        jdbc = mock(JdbcTemplate.class);
        AlertEvaluator evaluator = new AlertEvaluator(); // 真实阈值逻辑
        poll = new MetricPollEvaluator(ruleRepo, evaluator, lifecycle, dispatch, metricService, jdbc);
        // lifecycle.onSignal 回显候选事件 → 交 dispatch
        when(lifecycle.onSignal(any(AlertRule.class), any(AlertEvent.class)))
                .thenAnswer(inv -> Optional.of(inv.getArgument(1)));
    }

    private AlertRule pollRule(long id, long tenantId, double threshold) {
        AlertRule r = new AlertRule();
        r.setId(id);
        r.setTenantId(tenantId);
        r.setEnabled(1);
        r.setEvalMode("POLL");
        r.setSeverity("HIGH");
        r.setEvalIntervalSec(30);
        r.setConditionJson("{\"metric_key\":\"task.fail_rate\",\"comparator\":\"GT\",\"threshold\":" + threshold + "}");
        return r;
    }

    @Test
    void fires_when_real_value_over_threshold() {
        AlertRule rule = pollRule(1L, 10L, 5.0);
        when(metricService.findLatestByCode("task.fail_rate")).thenReturn(Optional.of(new AtomicMetric()));
        when(metricService.evaluate(any(AtomicMetric.class))).thenReturn(8.0);

        poll.evaluateRule(rule);

        var captor = org.mockito.ArgumentCaptor.forClass(AlertEvent.class);
        verify(dispatch).dispatch(captor.capture());
        AlertEvent ev = captor.getValue();
        assertThat(ev.getTenantId()).isEqualTo(10L);
        assertThat(ev.getValue()).isEqualTo("8.0"); // 真实值，非桩 0.0
        assertThat(ev.getSeverity()).isEqualTo("HIGH");
    }

    @Test
    void silent_when_real_value_under_threshold() {
        AlertRule rule = pollRule(2L, 10L, 5.0);
        when(metricService.findLatestByCode("task.fail_rate")).thenReturn(Optional.of(new AtomicMetric()));
        when(metricService.evaluate(any(AtomicMetric.class))).thenReturn(2.0);

        poll.evaluateRule(rule);

        verify(dispatch, never()).dispatch(any());
    }

    @Test
    void skips_and_no_fire_when_metric_missing() {
        AlertRule rule = pollRule(3L, 10L, 5.0);
        when(metricService.findLatestByCode(anyString())).thenReturn(Optional.empty());

        poll.evaluateRule(rule); // 不应抛异常

        verify(metricService, never()).evaluate(any());
        verify(dispatch, never()).dispatch(any());
    }

    @Test
    void skips_when_metric_value_non_numeric() {
        AlertRule rule = pollRule(4L, 10L, 5.0);
        when(metricService.findLatestByCode(anyString())).thenReturn(Optional.of(new AtomicMetric()));
        when(metricService.evaluate(any(AtomicMetric.class))).thenReturn("not-a-number");

        poll.evaluateRule(rule);

        verify(dispatch, never()).dispatch(any());
    }

    @Test
    void skips_when_guard_slot_already_claimed() {
        AlertRule rule = pollRule(5L, 10L, 5.0);
        // 别的 master 已认领本轮 → guard INSERT 冲突
        doThrow(new DataIntegrityViolationException("dup")).when(jdbc)
                .update(anyString(), any(), any(), any(), any());

        poll.evaluateRule(rule);

        verify(metricService, never()).findLatestByCode(anyString());
        verify(dispatch, never()).dispatch(any());
    }
}
