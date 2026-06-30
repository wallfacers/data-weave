package com.dataweave.alert.infrastructure;

import com.dataweave.alert.application.AlertDispatchService;
import com.dataweave.alert.domain.AlertChannel;
import com.dataweave.alert.domain.AlertRule;
import com.dataweave.alert.domain.AlertSilence;
import com.dataweave.alert.domain.repository.AlertChannelRepository;
import com.dataweave.alert.domain.repository.AlertRuleRepository;
import com.dataweave.alert.domain.repository.AlertSilenceRepository;
import com.dataweave.master.application.PlatformActionExecutor;
import com.dataweave.master.application.PlatformActionHandler;
import com.dataweave.master.domain.AgentAction;
import com.dataweave.master.i18n.Messages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 告警引擎的 {@link PlatformActionHandler} 实现：处理 agent 发起的 rule/channel/silence 写 + test-send。
 *
 * <p>注入到 master 的 {@code DefaultPlatformActionExecutor}（经 SPI 委派），master 编译期只见
 * {@link PlatformActionHandler} 接口，不反向依赖 alert 模块。
 */
@Component
public class AlertActionHandler implements PlatformActionHandler {

    private static final Logger log = LoggerFactory.getLogger(AlertActionHandler.class);
    private static final Set<String> SUPPORTED = Set.of("ALERT_RULE_WRITE", "ALERT_CHANNEL_WRITE", "ALERT_TEST_SEND");

    private final AlertRuleRepository ruleRepo;
    private final AlertChannelRepository channelRepo;
    private final AlertSilenceRepository silenceRepo;
    private final AlertDispatchService dispatchService;
    private final Messages messages;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AlertActionHandler(AlertRuleRepository ruleRepo, AlertChannelRepository channelRepo,
                              AlertSilenceRepository silenceRepo, AlertDispatchService dispatchService,
                              Messages messages) {
        this.ruleRepo = ruleRepo;
        this.channelRepo = channelRepo;
        this.silenceRepo = silenceRepo;
        this.dispatchService = dispatchService;
        this.messages = messages;
    }

    @Override
    public boolean supports(String actionType) {
        return actionType != null && SUPPORTED.contains(actionType.toUpperCase());
    }

    @Override
    public PlatformActionExecutor.ExecOutcome handle(AgentAction action, Locale locale) {
        String type = action.getActionType() != null ? action.getActionType().toUpperCase() : "";
        try {
            return switch (type) {
                case "ALERT_RULE_WRITE" -> handleRuleWrite(action, locale);
                case "ALERT_CHANNEL_WRITE" -> handleChannelWrite(action, locale);
                case "ALERT_TEST_SEND" -> handleTestSend(action, locale);
                default -> new PlatformActionExecutor.ExecOutcome(false,
                        messages.get("executor.unsupported_action", locale, action.getActionType()),
                        "{\"error\":\"unsupported-action\"}", null);
            };
        } catch (Exception e) {
            log.error("[AlertActionHandler] error handling {}: {}", type, e.getMessage(), e);
            return new PlatformActionExecutor.ExecOutcome(false, e.getMessage(),
                    "{\"error\":\"" + e.getClass().getSimpleName() + "\"}", null);
        }
    }

    @SuppressWarnings("unchecked")
    private PlatformActionExecutor.ExecOutcome handleRuleWrite(AgentAction action, Locale locale) {
        try {
            Map<String, Object> payload = objectMapper.readValue(action.getCommand(), Map.class);
            AlertRule rule = new AlertRule();
            rule.setTenantId(longVal(payload, "tenantId", 1L));
            rule.setName((String) payload.getOrDefault("name", ""));
            rule.setSignalSource((String) payload.getOrDefault("signalSource", "TASK_INSTANCE"));
            rule.setEvalMode((String) payload.getOrDefault("evalMode", "EVENT"));
            rule.setConditionJson((String) payload.get("conditionJson"));
            rule.setSeverity((String) payload.getOrDefault("severity", "WARNING"));
            rule.setEnabled(1);
            rule.setCreatedBy(action.getActor() != null ? parseLongOrNull(action.getActor()) : null);
            rule.setCreatedAt(LocalDateTime.now());
            AlertRule saved = ruleRepo.save(rule);
            return new PlatformActionExecutor.ExecOutcome(true,
                    messages.get("executor.alert_rule_write.success", locale, saved.getId()),
                    "{\"ruleId\":" + saved.getId() + "}", null);
        } catch (Exception e) {
            return new PlatformActionExecutor.ExecOutcome(false, e.getMessage(),
                    "{\"error\":\"rule_write_failed\"}", null);
        }
    }

    @SuppressWarnings("unchecked")
    private PlatformActionExecutor.ExecOutcome handleChannelWrite(AgentAction action, Locale locale) {
        try {
            Map<String, Object> payload = objectMapper.readValue(action.getCommand(), Map.class);
            AlertChannel channel = new AlertChannel();
            channel.setTenantId(longVal(payload, "tenantId", 1L));
            channel.setName((String) payload.getOrDefault("name", ""));
            channel.setType((String) payload.getOrDefault("type", "WEBHOOK"));
            channel.setConfigJson((String) payload.get("configJson"));
            channel.setEnabled(1);
            channel.setCreatedBy(action.getActor() != null ? parseLongOrNull(action.getActor()) : null);
            AlertChannel saved = channelRepo.save(channel);
            return new PlatformActionExecutor.ExecOutcome(true,
                    messages.get("executor.alert_rule_write.success", locale, saved.getId()),
                    "{\"channelId\":" + saved.getId() + "}", null);
        } catch (Exception e) {
            return new PlatformActionExecutor.ExecOutcome(false, e.getMessage(),
                    "{\"error\":\"channel_write_failed\"}", null);
        }
    }

    @SuppressWarnings("unchecked")
    private PlatformActionExecutor.ExecOutcome handleTestSend(AgentAction action, Locale locale) {
        try {
            Map<String, Object> payload = objectMapper.readValue(action.getCommand(), Map.class);
            Long channelId = longVal(payload, "channelId", null);
            if (channelId == null) {
                return new PlatformActionExecutor.ExecOutcome(false,
                        "missing channelId", "{\"error\":\"missing_channel_id\"}", null);
            }
            AlertChannel channel = channelRepo.findById(channelId).orElse(null);
            if (channel == null) {
                return new PlatformActionExecutor.ExecOutcome(false,
                        messages.get("alert.channel_not_found", locale, channelId),
                        "{\"error\":\"channel_not_found\"}", null);
            }
            var notif = dispatchService.testSend(channel);
            return new PlatformActionExecutor.ExecOutcome(true,
                    messages.get("executor.alert_test_send.success", locale, channelId),
                    "{\"channelId\":" + channelId + ",\"status\":\"" + notif.getStatus() + "\"}", null);
        } catch (Exception e) {
            return new PlatformActionExecutor.ExecOutcome(false, e.getMessage(),
                    "{\"error\":\"test_send_failed\"}", null);
        }
    }

    private Long longVal(Map<String, Object> m, String key, Long defaultVal) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Long.parseLong(s.trim()); } catch (NumberFormatException ignored) {}
        }
        return defaultVal;
    }

    private Long parseLongOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return null; }
    }
}
