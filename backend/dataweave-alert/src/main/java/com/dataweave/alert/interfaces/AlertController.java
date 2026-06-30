package com.dataweave.alert.interfaces;

import com.dataweave.alert.application.*;
import com.dataweave.alert.domain.*;
import com.dataweave.alert.domain.repository.*;
import com.dataweave.master.i18n.BizException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Alert engine REST controller: /api/alert/* (WebFlux + tenant isolation via exchange attributes).
 */
@RestController
@RequestMapping("/api/alert")
public class AlertController {

    private final AlertRuleRepository ruleRepo;
    private final AlertChannelRepository channelRepo;
    private final AlertRouteRepository routeRepo;
    private final AlertEventRepository eventRepo;
    private final AlertNotificationRepository notifRepo;
    private final AlertSilenceRepository silenceRepo;
    private final AlertRuleService ruleService;
    private final AlertLifecycleService lifecycleService;
    private final AlertDispatchService dispatchService;

    public AlertController(AlertRuleRepository ruleRepo, AlertChannelRepository channelRepo,
                           AlertRouteRepository routeRepo, AlertEventRepository eventRepo,
                           AlertNotificationRepository notifRepo, AlertSilenceRepository silenceRepo,
                           AlertRuleService ruleService, AlertLifecycleService lifecycleService,
                           AlertDispatchService dispatchService) {
        this.ruleRepo = ruleRepo;
        this.channelRepo = channelRepo;
        this.routeRepo = routeRepo;
        this.eventRepo = eventRepo;
        this.notifRepo = notifRepo;
        this.silenceRepo = silenceRepo;
        this.ruleService = ruleService;
        this.lifecycleService = lifecycleService;
        this.dispatchService = dispatchService;
    }

    private long tenantId(ServerWebExchange exchange) {
        Long tid = exchange.getAttribute("tenantId");
        if (tid == null) throw new BizException("alert.tenant_required");
        return tid;
    }

    // ─── Rules ─────────────────────────────────────────

    @GetMapping("/rules")
    public Mono<Map<String, Object>> listRules(ServerWebExchange exchange,
                                                @RequestParam(defaultValue = "0") int offset,
                                                @RequestParam(defaultValue = "20") int limit,
                                                @RequestParam(required = false) String signalSource,
                                                @RequestParam(required = false) Boolean enabled) {
        long tid = tenantId(exchange);
        var list = ruleService.list(tid, signalSource, enabled, offset, limit);
        return Mono.just(Map.of("code", 0, "data", Map.of("items", list, "total", ruleRepo.countByTenantId(tid))));
    }

    @GetMapping("/rules/{id}")
    public Mono<Map<String, Object>> getRule(@PathVariable Long id) {
        return Mono.just(Map.of("code", 0, "data", ruleService.get(id)));
    }

    @PostMapping("/rules")
    public Mono<Map<String, Object>> createRule(ServerWebExchange exchange, @RequestBody AlertRule rule) {
        rule.setTenantId(tenantId(exchange));
        rule.setCreatedBy(1L);
        rule.setCreatedAt(LocalDateTime.now());
        return Mono.just(Map.of("code", 0, "data", ruleService.create(rule)));
    }

    @PatchMapping("/rules/{id}")
    public Mono<Map<String, Object>> updateRule(@PathVariable Long id, @RequestBody AlertRule patch) {
        patch.setUpdatedBy(1L);
        return Mono.just(Map.of("code", 0, "data", ruleService.update(id, patch)));
    }

    @DeleteMapping("/rules/{id}")
    public Mono<Map<String, Object>> deleteRule(@PathVariable Long id) {
        ruleService.delete(id);
        return Mono.just(Map.of("code", 0, "data", null));
    }

    // ─── Channels ──────────────────────────────────────

    @GetMapping("/channels")
    public Mono<Map<String, Object>> listChannels(ServerWebExchange exchange) {
        return Mono.just(Map.of("code", 0, "data", channelRepo.findByTenantId(tenantId(exchange))));
    }

    @GetMapping("/channels/{id}")
    public Mono<Map<String, Object>> getChannel(@PathVariable Long id) {
        var c = channelRepo.findById(id).orElseThrow(() -> new BizException("alert.channel_not_found", id));
        return Mono.just(Map.of("code", 0, "data", c));
    }

    @PostMapping("/channels")
    public Mono<Map<String, Object>> createChannel(ServerWebExchange exchange, @RequestBody AlertChannel channel) {
        channel.setTenantId(tenantId(exchange));
        channel.setCreatedBy(1L);
        return Mono.just(Map.of("code", 0, "data", channelRepo.save(channel)));
    }

    @PatchMapping("/channels/{id}")
    public Mono<Map<String, Object>> updateChannel(@PathVariable Long id, @RequestBody AlertChannel patch) {
        var c = channelRepo.findById(id).orElseThrow(() -> new BizException("alert.channel_not_found", id));
        if (patch.getName() != null) c.setName(patch.getName());
        if (patch.getType() != null) c.setType(patch.getType());
        if (patch.getConfigJson() != null) c.setConfigJson(patch.getConfigJson());
        if (patch.getRateLimitPerMin() != null) c.setRateLimitPerMin(patch.getRateLimitPerMin());
        if (patch.getEnabled() != null) c.setEnabled(patch.getEnabled());
        c.setUpdatedBy(1L);
        return Mono.just(Map.of("code", 0, "data", channelRepo.save(c)));
    }

    @DeleteMapping("/channels/{id}")
    public Mono<Map<String, Object>> deleteChannel(@PathVariable Long id) {
        channelRepo.deleteById(id);
        return Mono.just(Map.of("code", 0, "data", null));
    }

    @PostMapping("/channels/{id}/test")
    public Mono<Map<String, Object>> testChannel(@PathVariable Long id) {
        var channel = channelRepo.findById(id).orElseThrow(() -> new BizException("alert.channel_not_found", id));
        AlertNotification notif = dispatchService.testSend(channel);
        return Mono.just(Map.of("code", 0, "data", Map.of(
                "outcome", "EXECUTED", "notification", notif,
                "status", notif.getStatus())));
    }

    // ─── Routes ────────────────────────────────────────

    @GetMapping("/routes")
    public Mono<Map<String, Object>> listRoutes(ServerWebExchange exchange) {
        return Mono.just(Map.of("code", 0, "data", routeRepo.findByTenantId(tenantId(exchange))));
    }

    @PostMapping("/routes")
    public Mono<Map<String, Object>> createRoute(ServerWebExchange exchange, @RequestBody AlertRoute route) {
        route.setTenantId(tenantId(exchange));
        route.setCreatedBy(1L);
        return Mono.just(Map.of("code", 0, "data", routeRepo.save(route)));
    }

    @PatchMapping("/routes/{id}")
    public Mono<Map<String, Object>> updateRoute(@PathVariable Long id, @RequestBody AlertRoute patch) {
        var r = routeRepo.findById(id).orElseThrow(() -> new BizException("alert.rule_not_found", id));
        if (patch.getMatchJson() != null) r.setMatchJson(patch.getMatchJson());
        if (patch.getChannelIds() != null) r.setChannelIds(patch.getChannelIds());
        if (patch.getSortOrder() != null) r.setSortOrder(patch.getSortOrder());
        if (patch.getEnabled() != null) r.setEnabled(patch.getEnabled());
        r.setUpdatedBy(1L);
        return Mono.just(Map.of("code", 0, "data", routeRepo.save(r)));
    }

    @DeleteMapping("/routes/{id}")
    public Mono<Map<String, Object>> deleteRoute(@PathVariable Long id) {
        routeRepo.deleteById(id);
        return Mono.just(Map.of("code", 0, "data", null));
    }

    // ─── Events ────────────────────────────────────────

    @GetMapping("/events")
    public Mono<Map<String, Object>> listEvents(ServerWebExchange exchange,
                                                 @RequestParam(required = false) String state,
                                                 @RequestParam(defaultValue = "0") int offset,
                                                 @RequestParam(defaultValue = "20") int limit) {
        long tid = tenantId(exchange);
        List<AlertEvent> events;
        int total;
        if (state != null && !state.isBlank()) {
            events = eventRepo.findByTenantIdAndState(tid, state, offset, limit);
            total = eventRepo.countByTenantIdAndState(tid, state);
        } else {
            events = eventRepo.findByTenantId(tid, offset, limit);
            total = eventRepo.countByTenantId(tid);
        }
        return Mono.just(Map.of("code", 0, "data", Map.of("items", events, "total", total)));
    }

    @GetMapping("/events/{id}")
    public Mono<Map<String, Object>> getEvent(@PathVariable Long id) {
        var e = eventRepo.findById(id).orElseThrow(() -> new BizException("alert.event_not_found", id));
        var notifications = notifRepo.findByEventId(e.getTenantId(), id);
        return Mono.just(Map.of("code", 0, "data", Map.of("event", e, "notifications", notifications)));
    }

    @PostMapping("/events/{id}/ack")
    public Mono<Map<String, Object>> ackEvent(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long ackedBy = body.get("ackedBy") instanceof Number n ? n.longValue() : 1L;
        if (!lifecycleService.ack(id, ackedBy)) {
            throw new BizException("alert.event_not_ackable", id, "current state not FIRING");
        }
        var e = eventRepo.findById(id).orElseThrow();
        return Mono.just(Map.of("code", 0, "data", e));
    }

    @GetMapping("/events/{id}/notifications")
    public Mono<Map<String, Object>> getEventNotifications(@PathVariable Long id) {
        var e = eventRepo.findById(id).orElseThrow(() -> new BizException("alert.event_not_found", id));
        return Mono.just(Map.of("code", 0, "data", notifRepo.findByEventId(e.getTenantId(), id)));
    }

    // ─── Silences ──────────────────────────────────────

    @GetMapping("/silences")
    public Mono<Map<String, Object>> listSilences(ServerWebExchange exchange) {
        return Mono.just(Map.of("code", 0, "data", silenceRepo.findActiveByTenantId(tenantId(exchange))));
    }

    @PostMapping("/silences")
    public Mono<Map<String, Object>> createSilence(ServerWebExchange exchange, @RequestBody AlertSilence silence) {
        if (silence.getEndsAt() != null && silence.getStartsAt() != null &&
                !silence.getEndsAt().isAfter(silence.getStartsAt())) {
            throw new BizException("alert.silence_invalid_window");
        }
        silence.setTenantId(tenantId(exchange));
        silence.setCreatedBy(1L);
        silence.setCreatedAt(LocalDateTime.now());
        return Mono.just(Map.of("code", 0, "data", silenceRepo.save(silence)));
    }

    @DeleteMapping("/silences/{id}")
    public Mono<Map<String, Object>> deleteSilence(@PathVariable Long id) {
        silenceRepo.deleteById(id);
        return Mono.just(Map.of("code", 0, "data", null));
    }
}
