package com.dataweave.alert.application;

import com.dataweave.alert.domain.AlertRule;
import com.dataweave.alert.domain.AlertEvent;
import com.dataweave.alert.domain.AlertState;
import com.dataweave.master.domain.signal.AlertSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Map;

/**
 * 告警评估器：条件匹配 + for_duration 去抖 + fingerprint 生成。
 */
@Service
public class AlertEvaluator {

    private static final Logger log = LoggerFactory.getLogger(AlertEvaluator.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 评估信号是否匹配规则的条件。
     */
    public boolean evaluate(AlertRule rule, AlertSignal signal) {
        if (rule.getEnabled() == null || rule.getEnabled() == 0) return false;
        if (!rule.getSignalSource().equals(mapSignalSource(signal.getType()))) return false;

        // POLL 模式不在这里评估（由 MetricPollEvaluator 轮询）
        if ("POLL".equals(rule.getEvalMode())) return false;
        // EVENT 模式：检查 condition_json 中的 event_type 和 filter
        return evaluateCondition(rule, signal);
    }

    /**
     * 评估 metric 值是否触发阈值（供 MetricPollEvaluator 调用）。
     */
    public boolean evaluateMetric(AlertRule rule, double currentValue) {
        if (rule.getEnabled() == null || rule.getEnabled() == 0) return false;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> cond = objectMapper.readValue(rule.getConditionJson(), Map.class);
            String comparator = (String) cond.getOrDefault("comparator", "GT");
            double threshold = ((Number) cond.getOrDefault("threshold", 0)).doubleValue();
            return switch (comparator) {
                case "GT" -> currentValue > threshold;
                case "GTE" -> currentValue >= threshold;
                case "LT" -> currentValue < threshold;
                case "LTE" -> currentValue <= threshold;
                case "EQ" -> currentValue == threshold;
                case "NE" -> currentValue != threshold;
                default -> false;
            };
        } catch (Exception e) {
            log.warn("Failed to evaluate metric condition for rule {}: {}", rule.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * 根据规则的 dedup_key_template 和信号上下文生成 fingerprint。
     */
    public String fingerprint(AlertRule rule, AlertSignal signal) {
        String template = rule.getDedupKeyTemplate();
        if (template == null || template.isBlank()) {
            template = rule.getSignalSource() + ":" + signal.getFingerprintHint();
        }
        // 简单模板替换：{taskId}、{metricKey} 等
        String rendered = renderTemplate(template, signal.getContext());
        return sha256(rule.getId() + ":" + rendered);
    }

    /**
     * 根据规则和 metric key 生成 fingerprint（供 MetricPollEvaluator 使用）。
     */
    public String fingerprint(AlertRule rule, String metricKey) {
        String template = rule.getDedupKeyTemplate();
        if (template == null || template.isBlank()) {
            template = "metric:" + metricKey;
        }
        String rendered = renderTemplate(template, Map.of("metricKey", metricKey));
        return sha256(rule.getId() + ":" + rendered);
    }

    private boolean evaluateCondition(AlertRule rule, AlertSignal signal) {
        String conditionJson = rule.getConditionJson();
        if (conditionJson == null || conditionJson.isBlank()) return true;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> cond = objectMapper.readValue(conditionJson, Map.class);
            String eventType = (String) cond.get("event_type");
            if (eventType != null && !eventType.equals(signal.getType().name())) return false;
            // filter 匹配（简单实现：检查 context 中的标签）
            @SuppressWarnings("unchecked")
            Map<String, Object> filter = (Map<String, Object>) cond.get("filter");
            if (filter != null) {
                for (var entry : filter.entrySet()) {
                    Object actual = signal.getContext().get(entry.getKey());
                    if (actual == null || !String.valueOf(actual).equals(String.valueOf(entry.getValue()))) {
                        return false;
                    }
                }
            }
            return true;
        } catch (Exception e) {
            log.warn("Failed to parse condition_json for rule {}: {}", rule.getId(), e.getMessage());
            return false;
        }
    }

    private String renderTemplate(String template, Map<String, Object> ctx) {
        String result = template;
        for (var entry : ctx.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        return result;
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

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
