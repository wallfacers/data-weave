package com.dataweave.alert.application;

import com.dataweave.alert.domain.AlertRule;
import com.dataweave.alert.domain.repository.AlertRuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Metric 轮询评估器：对 eval_mode=POLL 的规则定时评估。
 *
 * <p>HA 单点保证：评估前 INSERT {@code alert_poll_fire} guard 行，
 * 捕获 {@link DataIntegrityViolationException} = 别的 master 已认领本轮 → 跳过。
 * 镜像 {@code cron_fire} UNIQUE 冲突范式。
 */
@Service
public class MetricPollEvaluator {

    private static final Logger log = LoggerFactory.getLogger(MetricPollEvaluator.class);
    private final AlertRuleRepository ruleRepo;
    private final AlertEvaluator evaluator;
    private final AlertLifecycleService lifecycle;
    private final AlertDispatchService dispatch;
    private final JdbcTemplate jdbc;

    public MetricPollEvaluator(AlertRuleRepository ruleRepo, AlertEvaluator evaluator,
                               AlertLifecycleService lifecycle, AlertDispatchService dispatch,
                               JdbcTemplate jdbc) {
        this.ruleRepo = ruleRepo;
        this.evaluator = evaluator;
        this.lifecycle = lifecycle;
        this.dispatch = dispatch;
        this.jdbc = jdbc;
    }

    /**
     * 默认 30s 轮询一次（可配 {@code alert.poll.interval-ms}）。
     */
    @Scheduled(fixedDelayString = "${alert.poll.interval-ms:30000}", initialDelayString = "${alert.poll.initial-ms:15000}")
    public void evaluate() {
        List<AlertRule> rules = ruleRepo.findByTenantIdAndEvalModeAndEnabled(null, "POLL", 1);
        // tenantId 为 null 时查询所有租户...但该查询当前按 tenant_id 精确匹配，
        // 这里改为遍历所有租户获取规则。简化实现：假设只查 tenant 1
        // TODO: 遍历所有租户的 POLL 规则
        List<AlertRule> allRules = ruleRepo.findByTenantIdAndEvalModeAndEnabled(1L, "POLL", 1);
        for (AlertRule rule : allRules) {
            evaluateRule(rule);
        }
    }

    void evaluateRule(AlertRule rule) {
        // 1. claim guard slot
        Instant now = Instant.now();
        long slotSec = (now.getEpochSecond() / getEvalIntervalSec(rule)) * getEvalIntervalSec(rule);
        LocalDateTime pollSlot = LocalDateTime.ofInstant(Instant.ofEpochSecond(slotSec), ZoneId.systemDefault());

        try {
            jdbc.update(
                    "INSERT INTO alert_poll_fire (rule_id, poll_slot, fired_at, created_at) VALUES (?,?,?,?)",
                    rule.getId(), pollSlot, LocalDateTime.now(), LocalDateTime.now());
        } catch (DataIntegrityViolationException e) {
            // 别的 master 已认领本轮 → 跳过
            return;
        }

        try {
            // 2. 评估 metric（简化：这里需要真实的 metric 数据源）
            // v1: 通过 condition_json 中 metric_key 查询指标值（桩）
            double currentValue = fetchMetricValue(rule);
            if (evaluator.evaluateMetric(rule, currentValue)) {
                // 3. 触发告警
                String metricKey = extractMetricKey(rule);
                String fp = evaluator.fingerprint(rule, metricKey);
                com.dataweave.alert.domain.AlertEvent event = new com.dataweave.alert.domain.AlertEvent();
                event.setTenantId(rule.getTenantId());
                event.setRuleId(rule.getId());
                event.setSeverity(rule.getSeverity());
                event.setFingerprint(fp);
                event.setValue(String.valueOf(currentValue));
                event.setContextJson("{\"metric_key\":\"" + metricKey + "\",\"value\":" + currentValue + "}");

                lifecycle.onSignal(rule, event).ifPresent(dispatch::dispatch);
            }
        } catch (Exception e) {
            log.warn("[MetricPoll] evaluation error for rule {}: {}", rule.getId(), e.getMessage());
        }
    }

    private double fetchMetricValue(AlertRule rule) {
        // v1 桩：返回 0（后续接入真实 metrics）
        return 0.0;
    }

    private String extractMetricKey(AlertRule rule) {
        try {
            tools.jackson.databind.ObjectMapper om = new tools.jackson.databind.ObjectMapper();
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> cond = om.readValue(rule.getConditionJson(), java.util.Map.class);
            return (String) cond.getOrDefault("metric_key", "unknown");
        } catch (Exception e) {
            return "unknown";
        }
    }

    private int getEvalIntervalSec(AlertRule rule) {
        return rule.getEvalIntervalSec() != null && rule.getEvalIntervalSec() > 0
                ? rule.getEvalIntervalSec() : 30;
    }
}
