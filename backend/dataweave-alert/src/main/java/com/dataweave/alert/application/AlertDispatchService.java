package com.dataweave.alert.application;

import com.dataweave.alert.domain.AlertChannel;
import com.dataweave.alert.domain.AlertEvent;
import com.dataweave.alert.domain.AlertNotification;
import com.dataweave.alert.domain.AlertRoute;
import com.dataweave.alert.domain.repository.AlertChannelRepository;
import com.dataweave.alert.domain.repository.AlertNotificationRepository;
import com.dataweave.alert.domain.repository.AlertRouteRepository;
import com.dataweave.alert.infrastructure.channel.ChannelDispatcher;
import com.dataweave.alert.infrastructure.channel.DispatchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 告警分发服务：路由匹配 + 多通道投递 + 指数退避重试 + 令牌桶限流 + 投递审计。
 */
@Service
public class AlertDispatchService {

    private static final Logger log = LoggerFactory.getLogger(AlertDispatchService.class);
    private static final int MAX_RETRIES = 3;
    private static final long BASE_BACKOFF_MS = 1000;

    private final AlertRouteRepository routeRepo;
    private final AlertChannelRepository channelRepo;
    private final AlertNotificationRepository notifRepo;
    private final Map<String, ChannelDispatcher> dispatchers;
    private final ObjectMapper objectMapper = new ObjectMapper();
    // 简单令牌桶：channelId → (tokens, lastRefillMs)
    private final ConcurrentHashMap<Long, TokenBucket> buckets = new ConcurrentHashMap<>();

    public AlertDispatchService(AlertRouteRepository routeRepo, AlertChannelRepository channelRepo,
                                AlertNotificationRepository notifRepo, List<ChannelDispatcher> dispatcherList) {
        this.routeRepo = routeRepo;
        this.channelRepo = channelRepo;
        this.notifRepo = notifRepo;
        this.dispatchers = new HashMap<>();
        for (ChannelDispatcher d : dispatcherList) {
            this.dispatchers.put(d.channelType(), d);
        }
    }

    /**
     * 按路由分发告警事件到匹配通道。
     *
     * @return 每条通道的分发通知
     */
    public List<AlertNotification> dispatch(AlertEvent event) {
        List<AlertNotification> notifications = new ArrayList<>();
        List<AlertRoute> routes = routeRepo.findByTenantId(event.getTenantId());

        Set<Long> channelIds = findMatchingChannels(event, routes);
        if (channelIds.isEmpty()) {
            log.info("[AlertDispatch] no matching channels for event {} severity={}", event.getId(), event.getSeverity());
            return notifications;
        }

        List<AlertChannel> channels = channelRepo.findByIds(event.getTenantId(), new ArrayList<>(channelIds));
        for (AlertChannel channel : channels) {
            if (channel.getEnabled() == null || channel.getEnabled() == 0) continue;
            if (!checkRateLimit(channel)) {
                log.warn("[AlertDispatch] rate limited: channel={}", channel.getId());
                continue;
            }
            AlertNotification notif = sendWithRetry(event, channel);
            notifications.add(notif);
        }
        return notifications;
    }

    /**
     * Test-send：直发指定通道（不经过路由匹配）。
     */
    public AlertNotification testSend(AlertChannel channel) {
        AlertEvent dummyEvent = new AlertEvent();
        dummyEvent.setTenantId(channel.getTenantId());
        dummyEvent.setSeverity("INFO");
        dummyEvent.setFingerprint("test:" + UUID.randomUUID());
        dummyEvent.setId(0L); // 非持久化事件
        return sendWithRetry(dummyEvent, channel);
    }

    /**
     * 027：直发指定通道（不经路由匹配），供事件中心订阅触达复用既有重试/限流/审计。
     */
    public AlertNotification dispatchToChannel(AlertEvent event, AlertChannel channel) {
        return sendWithRetry(event, channel);
    }

    private Set<Long> findMatchingChannels(AlertEvent event, List<AlertRoute> routes) {
        Set<Long> result = new LinkedHashSet<>();
        for (AlertRoute route : routes) {
            if (route.getEnabled() == null || route.getEnabled() == 0) continue;
            if (matchesRoute(route, event)) {
                String channelIdsStr = route.getChannelIds();
                if (channelIdsStr != null && !channelIdsStr.isBlank()) {
                    for (String s : channelIdsStr.split(",")) {
                        try { result.add(Long.parseLong(s.trim())); } catch (NumberFormatException ignored) {}
                    }
                }
            }
        }
        return result;
    }

    private boolean matchesRoute(AlertRoute route, AlertEvent event) {
        try {
            String matchJson = route.getMatchJson();
            if (matchJson == null || matchJson.isBlank()) return false;
            @SuppressWarnings("unchecked")
            Map<String, Object> match = objectMapper.readValue(matchJson, Map.class);
            String severityMatch = (String) match.get("severity");
            if (severityMatch != null && !severityMatch.equalsIgnoreCase(event.getSeverity())) {
                return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private AlertNotification sendWithRetry(AlertEvent event, AlertChannel channel) {
        AlertNotification notif = new AlertNotification();
        notif.setTenantId(channel.getTenantId());
        notif.setEventId(event.getId());
        notif.setChannelId(channel.getId());
        notif.setStatus("RETRYING");
        notif.setAttempts(0);
        LocalDateTime now = LocalDateTime.now();
        notif.setSentAt(now);
        notif.setCreatedAt(now);

        ChannelDispatcher dispatcher = dispatchers.get(channel.getType());
        if (dispatcher == null) {
            notif.setStatus("FAILED");
            notif.setError("no dispatcher for channel type: " + channel.getType());
            return notifRepo.save(notif);
        }

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            notif.setAttempts(attempt + 1);
            try {
                DispatchResult result = dispatcher.dispatch(event, channel);
                if (result.success()) {
                    notif.setStatus("SENT");
                    notif.setResponseDigest(result.responseDigest());
                    notif.setSentAt(LocalDateTime.now());
                    return notifRepo.save(notif);
                }
                // 026: 未配置（缺收件人/未配 SMTP）短路——不重试、不记 FAILED，标 SKIPPED
                if (!result.configured()) {
                    notif.setStatus("SKIPPED");
                    notif.setError(result.error());
                    log.warn("[AlertDispatch] channel {} not configured: {}", channel.getId(), result.error());
                    return notifRepo.save(notif);
                }
                notif.setError(result.error());
            } catch (Exception e) {
                notif.setError(e.getMessage());
                log.warn("[AlertDispatch] attempt {} failed for channel {}: {}", attempt + 1, channel.getId(), e.getMessage());
            }
            if (attempt < MAX_RETRIES - 1) {
                long backoffMs = BASE_BACKOFF_MS * (1L << attempt); // 1s, 2s, 4s
                try { Thread.sleep(backoffMs); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
            }
        }
        notif.setStatus("FAILED");
        return notifRepo.save(notif);
    }

    private boolean checkRateLimit(AlertChannel channel) {
        int ratePerMin = channel.getRateLimitPerMin() != null ? channel.getRateLimitPerMin() : 60;
        TokenBucket bucket = buckets.computeIfAbsent(channel.getId(), k -> new TokenBucket(ratePerMin));
        return bucket.tryConsume();
    }

    private static class TokenBucket {
        private final int maxTokens;
        private double tokens;
        private long lastRefill;

        TokenBucket(int maxTokens) {
            this.maxTokens = maxTokens;
            this.tokens = maxTokens;
            this.lastRefill = System.currentTimeMillis();
        }

        synchronized boolean tryConsume() {
            long now = System.currentTimeMillis();
            double elapsed = (now - lastRefill) / 1000.0;
            tokens = Math.min(maxTokens, tokens + elapsed * (maxTokens / 60.0));
            lastRefill = now;
            if (tokens >= 1) {
                tokens -= 1;
                return true;
            }
            return false;
        }
    }
}
