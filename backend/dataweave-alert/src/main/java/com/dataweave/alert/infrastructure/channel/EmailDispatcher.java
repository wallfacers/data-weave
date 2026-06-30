package com.dataweave.alert.infrastructure.channel;

import com.dataweave.alert.domain.AlertChannel;
import com.dataweave.alert.domain.AlertEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * EMAIL 通道分发器：经 {@link JavaMailSender} 真实投递。
 *
 * <p>收件人取 {@code channel.configJson}（{@code recipients}/{@code cc}/{@code subjectPrefix}）；
 * SMTP 连接由应用配置 {@code spring.mail.*} 提供（不内置邮件服务器）。
 * 未配置（无 mail sender 或无收件人）→ {@link DispatchResult#notConfigured}，不抛错、不假成功。
 */
@Component
public class EmailDispatcher implements ChannelDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EmailDispatcher.class);
    private static final String DEFAULT_SUBJECT_PREFIX = "[Weft Alert]";

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EmailDispatcher(ObjectProvider<JavaMailSender> mailSenderProvider) {
        this.mailSenderProvider = mailSenderProvider;
    }

    @Override
    public String channelType() {
        return "EMAIL";
    }

    @Override
    public DispatchResult dispatch(AlertEvent event, AlertChannel channel) {
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            return DispatchResult.notConfigured("mail sender not configured (spring.mail.*)");
        }

        EmailConfig cfg = parseConfig(channel.getConfigJson());
        if (cfg.recipients.isEmpty()) {
            return DispatchResult.notConfigured("no recipients in channel config");
        }

        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setTo(cfg.recipients.toArray(new String[0]));
            if (!cfg.cc.isEmpty()) {
                msg.setCc(cfg.cc.toArray(new String[0]));
            }
            msg.setSubject(cfg.subjectPrefix + " " + severityOf(event) + " - " + channel.getName());
            msg.setText(buildBody(event, channel));
            mailSender.send(msg);
            log.info("[EmailDispatcher] sent to {} for event {} severity={}",
                    cfg.recipients, event.getId(), event.getSeverity());
            return DispatchResult.sent("email sent to " + cfg.recipients.size() + " recipient(s)");
        } catch (Exception e) {
            log.warn("[EmailDispatcher] send failed for channel {}: {}", channel.getId(), e.getMessage());
            return DispatchResult.failed("email send failed: " + e.getMessage());
        }
    }

    /** 邮件正文：含规则名/severity/触发值/时间/上下文（契约 email-channel-config）。 */
    private String buildBody(AlertEvent event, AlertChannel channel) {
        StringBuilder sb = new StringBuilder();
        sb.append("Channel: ").append(channel.getName()).append('\n');
        sb.append("Severity: ").append(severityOf(event)).append('\n');
        sb.append("Value: ").append(event.getValue()).append('\n');
        sb.append("Fingerprint: ").append(event.getFingerprint()).append('\n');
        sb.append("Time: ").append(LocalDateTime.now()).append('\n');
        if (event.getContextJson() != null) {
            sb.append("Context: ").append(event.getContextJson()).append('\n');
        }
        return sb.toString();
    }

    private String severityOf(AlertEvent event) {
        return event.getSeverity() == null ? "UNKNOWN" : event.getSeverity();
    }

    @SuppressWarnings("unchecked")
    private EmailConfig parseConfig(String configJson) {
        EmailConfig cfg = new EmailConfig();
        if (configJson == null || configJson.isBlank()) {
            return cfg;
        }
        try {
            Map<String, Object> root = objectMapper.readValue(configJson, Map.class);
            cfg.recipients = readArray(root.get("recipients"));
            cfg.cc = readArray(root.get("cc"));
            Object prefix = root.get("subjectPrefix");
            if (prefix instanceof String s && !s.isBlank()) {
                cfg.subjectPrefix = s;
            }
        } catch (Exception e) {
            log.warn("[EmailDispatcher] invalid config_json, treating as no recipients: {}", e.getMessage());
        }
        return cfg;
    }

    private List<String> readArray(Object node) {
        List<String> out = new ArrayList<>();
        if (node instanceof List<?> list) {
            for (Object o : list) {
                if (o != null && !o.toString().isBlank()) {
                    out.add(o.toString());
                }
            }
        }
        return out;
    }

    private static final class EmailConfig {
        List<String> recipients = new ArrayList<>();
        List<String> cc = new ArrayList<>();
        String subjectPrefix = DEFAULT_SUBJECT_PREFIX;
    }
}
