package com.dataweave.master.quality.application;

import com.dataweave.master.domain.signal.AlertSignal;
import com.dataweave.master.quality.domain.QualityCheckResult;
import com.dataweave.master.quality.domain.QualityRule;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

/**
 * 027 US2：QualitySignalEmitter 发布的是统一的 {@code domain.signal.AlertSignal}
 * （而非孤儿 {@code quality.domain.AlertSignal}）——证明质量信号此后能被 AlertSignalListener 消费。
 */
class QualitySignalUnifiedTest {

    @Test
    void emits_unified_domain_signal_with_quality_failed_type() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        QualitySignalEmitter emitter = new QualitySignalEmitter(publisher);

        QualityRule rule = mock(QualityRule.class);
        when(rule.getDatasetRef()).thenReturn("db.orders");
        when(rule.getSeverity()).thenReturn("HIGH");
        when(rule.getAction()).thenReturn("BLOCK");

        QualityCheckResult result = mock(QualityCheckResult.class);
        when(result.getTenantId()).thenReturn(42L);
        when(result.getRuleId()).thenReturn(7L);
        when(result.getMeasuredValue()).thenReturn("0.83");

        emitter.emit(rule, result);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(captor.capture());
        Object published = captor.getValue();

        // 关键：发布的是统一信号类型（不再是孤儿 quality.domain.AlertSignal）
        assertThat(published).isInstanceOf(AlertSignal.class);
        AlertSignal signal = (AlertSignal) published;
        assertThat(signal.getType()).isEqualTo(AlertSignal.Type.QUALITY_FAILED);
        assertThat(signal.getTenantId()).isEqualTo(42L);
        assertThat(signal.getFingerprintHint()).isEqualTo("db.orders");
        assertThat(signal.getSeverityHint()).isEqualTo("HIGH");
        assertThat(signal.getContext()).containsEntry("ruleId", 7L);
    }
}
