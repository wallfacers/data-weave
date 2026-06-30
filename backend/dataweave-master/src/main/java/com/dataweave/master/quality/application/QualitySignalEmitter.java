package com.dataweave.master.quality.application;

import com.dataweave.master.quality.domain.AlertSignal;
import com.dataweave.master.quality.domain.AlertSignal.Type;
import com.dataweave.master.quality.domain.QualityCheckResult;
import com.dataweave.master.quality.domain.QualityRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * QUALITY_FAILED 信号发射器（022→021 接缝 D4，research D4）。
 *
 * <p>022 是产生方：任何断言 FAIL（BLOCK 或 WARN 均）时 publish {@link AlertSignal}（Type=QUALITY_FAILED）。
 * 021 是消费方：{@code AlertSignalListener @EventListener} 匹配 signal_source 告警规则。
 *
 * <p>022 只产生事件 + 携带上下文；通知规则/分发/去重/静默全在 021（spec 范围边界，FR-006）。
 */
@Component
public class QualitySignalEmitter {

    private static final Logger log = LoggerFactory.getLogger(QualitySignalEmitter.class);

    private final ApplicationEventPublisher publisher;

    public QualitySignalEmitter(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * @param rule   断言定义（取 severityHint）
     * @param result 结果（取 measuredValue/expected；signalEmitted 幂等由调用方管理）
     */
    public void emit(QualityRule rule, QualityCheckResult result) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("ruleId", result.getRuleId());
        context.put("runId", result.getRunId());
        context.put("resultId", result.getId());
        context.put("datasetId", result.getId() != null ? result.getId() : null);
        context.put("datasetRef", rule.getDatasetRef());
        context.put("assertionType", result.getAssertionType());
        context.put("measuredValue", result.getMeasuredValue());
        context.put("expected", result.getExpected());
        context.put("action", rule.getAction());
        context.put("message", result.getMessage());

        AlertSignal signal = new AlertSignal(
                Type.QUALITY_FAILED,
                result.getTenantId(),
                rule.getDatasetRef(),         // fingerprintHint=datasetRef（参与 021 fingerprint 去重）
                rule.getSeverity(),            // severityHint=rule.severity（021 规则可覆盖）
                context,
                Instant.now());

        log.info("[QualitySignal] QUALITY_FAILED tenant={} datasetRef={} rule={} measured={}",
                result.getTenantId(), rule.getDatasetRef(), result.getRuleId(), result.getMeasuredValue());
        publisher.publishEvent(signal);
    }
}
