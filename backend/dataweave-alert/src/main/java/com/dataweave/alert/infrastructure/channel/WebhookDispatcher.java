package com.dataweave.alert.infrastructure.channel;

import com.dataweave.alert.domain.AlertChannel;
import com.dataweave.alert.domain.AlertEvent;
import com.dataweave.master.i18n.Messages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Webhook 通道分发器基类：钉钉/企微/飞书统一为 webhook 机器人子类型。
 * 复用现有 {@link WebClient} @Bean。
 */
@Component
public class WebhookDispatcher implements ChannelDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Messages messages;

    public WebhookDispatcher(WebClient.Builder webClientBuilder, Messages messages) {
        this.webClientBuilder = webClientBuilder;
        this.messages = messages;
    }

    @Override
    public String channelType() {
        return "WEBHOOK";
    }

    @Override
    public DispatchResult dispatch(AlertEvent event, AlertChannel channel) {
        try {
            String configJson = channel.getConfigJson();
            @SuppressWarnings("unchecked")
            Map<String, Object> config = configJson != null && !configJson.isBlank()
                    ? objectMapper.readValue(configJson, Map.class)
                    : Map.of();
            String url = (String) config.get("url");
            if (url == null || url.isBlank()) {
                return DispatchResult.failed("webhook url not configured");
            }

            Map<String, Object> body = buildBody(event, channel);
            String rawBody = objectMapper.writeValueAsString(body);

            String response = webClientBuilder.build()
                    .post()
                    .uri(url)
                    .header("Content-Type", "application/json")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            String digest = response != null && response.length() > 500
                    ? response.substring(0, 500) : response;
            log.info("[WebhookDispatcher] sent to {}: status OK, digest={}", channel.getName(), digest);
            return DispatchResult.sent(digest);
        } catch (Exception e) {
            log.warn("[WebhookDispatcher] dispatch failed to {}: {}", channel.getName(), e.getMessage());
            return DispatchResult.failed(e.getMessage());
        }
    }

    protected Map<String, Object> buildBody(AlertEvent event, AlertChannel channel) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("msgtype", "text");
        Map<String, Object> text = new LinkedHashMap<>();
        String msg = messages.get("alert.notify.event", Locale.getDefault(),
                event.getSeverity(), event.getFingerprint());
        text.put("content", msg);
        body.put("text", text);
        return body;
    }
}
