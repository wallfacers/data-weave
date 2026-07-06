package com.dataweave.alert.application;

import com.dataweave.alert.domain.AlertRule;
import com.dataweave.alert.domain.repository.AlertRuleRepository;
import com.dataweave.master.domain.signal.AlertSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 消费 master 侧发出的 {@link AlertSignal}，匹配规则 → 评估 → 生命周期 → 分发。
 */
@Component
public class AlertSignalListener {

    private static final Logger log = LoggerFactory.getLogger(AlertSignalListener.class);
    private final AlertRuleRepository ruleRepo;
    private final AlertEvaluator evaluator;
    private final AlertLifecycleService lifecycle;
    private final AlertDispatchService dispatch;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AlertSignalListener(AlertRuleRepository ruleRepo, AlertEvaluator evaluator,
                               AlertLifecycleService lifecycle, AlertDispatchService dispatch) {
        this.ruleRepo = ruleRepo;
        this.evaluator = evaluator;
        this.lifecycle = lifecycle;
        this.dispatch = dispatch;
    }

    @EventListener
    public void onAlertSignal(AlertSignal signal) {
        log.debug("[AlertSignalListener] received: type={} tenant={}", signal.getType(), signal.getTenantId());
        try {
            // 查匹配的 EVENT 模式规则
            String signalSource = mapSignalSource(signal.getType());
            List<AlertRule> rules = ruleRepo.findByTenantIdAndSignalSourceAndEnabled(
                    signal.getTenantId(), signalSource, 1);
            for (AlertRule rule : rules) {
                if (!evaluator.evaluate(rule, signal)) continue;

                String fp = evaluator.fingerprint(rule, signal);
                com.dataweave.alert.domain.AlertEvent event = new com.dataweave.alert.domain.AlertEvent();
                event.setTenantId(rule.getTenantId());
                event.setRuleId(rule.getId());
                event.setSeverity(signal.getSeverityHint() != null ? signal.getSeverityHint() : rule.getSeverity());
                event.setFingerprint(fp);
                event.setValue((String) signal.getContext().get("value"));
                event.setContextJson(toJson(signal.getContext()));

                lifecycle.onSignal(rule, event).ifPresent(dispatch::dispatch);
            }
        } catch (Exception e) {
            log.error("[AlertSignalListener] error processing signal type={}: {}", signal.getType(), e.getMessage(), e);
        }
    }

    private String mapSignalSource(AlertSignal.Type type) {
        return switch (type) {
            case TASK_FAILED, TASK_TIMEOUT -> "TASK_INSTANCE";
            case SLA_BREACH -> "SLA_BREACH";
            case WORKFLOW_STATE -> "WORKFLOW_INSTANCE";
            case NODE_OFFLINE -> "NODE_OFFLINE";
            case METRIC_BREACH -> "METRIC";
            case QUALITY_FAILED -> "QUALITY_FAILED";
        };
    }

    private String toJson(Map<String, Object> ctx) {
        try {
            return objectMapper.writeValueAsString(ctx);
        } catch (Exception e) {
            return "{}";
        }
    }
}
