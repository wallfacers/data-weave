package com.dataweave.alert.application;

import com.dataweave.alert.domain.AlertRule;
import com.dataweave.alert.domain.repository.AlertRuleRepository;
import com.dataweave.master.application.MetricService;
import com.dataweave.master.domain.AtomicMetric;
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
import java.util.Optional;

/**
 * Metric 轮询评估器：对 eval_mode=POLL 的规则定时评估（全租户）。
 *
 * <p>取值复用 master {@link MetricService#findLatestByCode}+{@link MetricService#evaluate}（只读，进程内）。
 * 取不到/非数值/异常 → 跳过该规则 + WARN，不误报、不阻断其它规则（FR-003）。
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
    private final MetricService metricService;
    private final JdbcTemplate jdbc;

    public MetricPollEvaluator(AlertRuleRepository ruleRepo, AlertEvaluator evaluator,
                               AlertLifecycleService lifecycle, AlertDispatchService dispatch,
                               MetricService metricService, JdbcTemplate jdbc) {
        this.ruleRepo = ruleRepo;
        this.evaluator = evaluator;
        this.lifecycle = lifecycle;
        this.dispatch = dispatch;
        this.metricService = metricService;
        this.jdbc = jdbc;
    }

    /**
     * 默认 30s 轮询一次（可配 {@code alert.poll.interval-ms}）。遍历**所有租户**的启用 POLL 规则。
     */
    @Scheduled(fixedDelayString = "${alert.poll.interval-ms:30000}", initialDelayString = "${alert.poll.initial-ms:15000}")
    public void evaluate() {
        List<AlertRule> rules = ruleRepo.findByEvalModeAndEnabled("POLL", 1);
        for (AlertRule rule : rules) {
            evaluateRule(rule);
        }
    }

    void evaluateRule(AlertRule rule) {
        // 1. claim guard slot（HA 去重，按 rule_id 唯一，与租户无关）
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
            // 2. 取真实指标值（替代桩）；取不到 → 跳过 + WARN，不触发
            String metricKey = extractMetricKey(rule);
            Double currentValue = fetchMetricValue(rule, metricKey);
            if (currentValue == null) {
                return; // fetchMetricValue 已记 WARN
            }
            // 3. 阈值判定
            if (evaluator.evaluateMetric(rule, currentValue)) {
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

    /**
     * 取规则指标的真实当前值。{@code Optional.empty} / 非数值 / 异常 → 返回 {@code null}（跳过 + WARN）。
     */
    Double fetchMetricValue(AlertRule rule, String metricKey) {
        try {
            Optional<AtomicMetric> metricOpt = metricService.findLatestByCode(metricKey);
            if (metricOpt.isEmpty()) {
                log.warn("[MetricPoll] metric not found: {} (rule {}), skipping", metricKey, rule.getId());
                return null;
            }
            Object raw = metricService.evaluate(metricOpt.get());
            Double val = toDouble(raw);
            if (val == null) {
                log.warn("[MetricPoll] non-numeric metric value for {}: {} (rule {}), skipping",
                        metricKey, raw, rule.getId());
            }
            return val;
        } catch (Exception e) {
            log.warn("[MetricPoll] fetch metric value failed for rule {} (metric_key={}): {}",
                    rule.getId(), metricKey, e.getMessage());
            return null;
        }
    }

    private static Double toDouble(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(raw.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
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
