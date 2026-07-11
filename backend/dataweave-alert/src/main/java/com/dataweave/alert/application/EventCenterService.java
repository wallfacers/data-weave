package com.dataweave.alert.application;

import com.dataweave.alert.domain.AlertChannel;
import com.dataweave.alert.domain.AlertEvent;
import com.dataweave.alert.domain.EventSubscription;
import com.dataweave.alert.domain.HealthEvent;
import com.dataweave.alert.domain.repository.AlertChannelRepository;
import com.dataweave.alert.domain.repository.EventSubscriptionRepository;
import com.dataweave.alert.domain.repository.HealthEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 事件中心应用服务（027）：查询 + 订阅 CRUD + 命中订阅经 026 通道分发。
 */
@Service
public class EventCenterService {

    private static final Logger log = LoggerFactory.getLogger(EventCenterService.class);

    // severity 排序（事件 severity rank ≥ 订阅 min rank 才触达）；未知 → 0
    private static final Map<String, Integer> SEVERITY_RANK = Map.of(
            "INFO", 1, "LOW", 2, "MEDIUM", 3, "WARNING", 4, "HIGH", 5, "CRITICAL", 6);

    private final HealthEventRepository eventRepo;
    private final EventSubscriptionRepository subRepo;
    private final AlertChannelRepository channelRepo;
    private final AlertDispatchService dispatchService;

    public EventCenterService(HealthEventRepository eventRepo, EventSubscriptionRepository subRepo,
                              AlertChannelRepository channelRepo, AlertDispatchService dispatchService) {
        this.eventRepo = eventRepo;
        this.subRepo = subRepo;
        this.channelRepo = channelRepo;
        this.dispatchService = dispatchService;
    }

    // ── 查询 ──
    public Map<String, Object> query(long tenantId, String type, String severity, String refKind, String refId,
                                     LocalDateTime from, LocalDateTime to, boolean incidentOnly, int offset, int limit) {
        List<HealthEvent> items = eventRepo.query(tenantId, type, severity, refKind, refId, from, to, incidentOnly, offset, limit);
        int total = eventRepo.count(tenantId, type, severity, refKind, refId, from, to, incidentOnly);
        return Map.of("items", items, "total", total);
    }

    // ── 订阅 CRUD ──
    public EventSubscription subscribe(EventSubscription s) { return subRepo.save(s); }

    public List<EventSubscription> listSubscriptions(long tenantId) { return subRepo.findByTenantId(tenantId); }

    public int unsubscribe(Long id) { return subRepo.deleteById(id); }

    /**
     * 持久化后匹配订阅并经 026 通道分发。任一分发失败不阻断其它订阅（FR-009）。
     */
    public void matchAndDispatch(HealthEvent event) {
        List<EventSubscription> subs;
        try {
            subs = subRepo.findEnabledByTenantId(event.getTenantId());
        } catch (Exception e) {
            log.warn("[EventCenter] load subscriptions failed for tenant {}: {}", event.getTenantId(), e.getMessage());
            return;
        }
        for (EventSubscription sub : subs) {
            if (!matches(sub, event)) continue;
            try {
                Optional<AlertChannel> channel = channelRepo.findById(sub.getChannelId());
                if (channel.isEmpty() || channel.get().getEnabled() == null || channel.get().getEnabled() == 0) {
                    log.warn("[EventCenter] subscription {} channel {} missing/disabled, skip",
                            sub.getId(), sub.getChannelId());
                    continue;
                }
                dispatchService.dispatchToChannel(toAlertEvent(event), channel.get());
            } catch (Exception e) {
                // 分发失败不阻断持久化与其它订阅
                log.warn("[EventCenter] dispatch failed for subscription {}: {}", sub.getId(), e.getMessage());
            }
        }
    }

    boolean matches(EventSubscription sub, HealthEvent event) {
        if (notBlank(sub.getTypeFilter()) && !sub.getTypeFilter().equals(event.getType())) return false;
        if (notBlank(sub.getMinSeverity()) && rank(event.getSeverity()) < rank(sub.getMinSeverity())) return false;
        if (notBlank(sub.getRefKind()) && !sub.getRefKind().equals(event.getRefKind())) return false;
        if (notBlank(sub.getRefId()) && !sub.getRefId().equals(event.getRefId())) return false;
        return true;
    }

    private AlertEvent toAlertEvent(HealthEvent e) {
        AlertEvent ev = new AlertEvent();
        ev.setId(0L); // 非持久化告警事件（事件中心旁路触达）
        ev.setTenantId(e.getTenantId());
        ev.setSeverity(e.getSeverity());
        ev.setFingerprint(e.getFingerprint());
        ev.setValue(e.getSummary());
        ev.setContextJson(e.getContextJson());
        return ev;
    }

    private static int rank(String severity) {
        return severity == null ? 0 : SEVERITY_RANK.getOrDefault(severity.toUpperCase(), 0);
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
}
