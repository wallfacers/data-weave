package com.dataweave.alert.interfaces;

import com.dataweave.alert.application.*;
import com.dataweave.alert.domain.*;
import com.dataweave.alert.domain.repository.*;
import com.dataweave.master.application.ProjectScope;
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
    private final ProjectScope projectScope;

    public AlertController(AlertRuleRepository ruleRepo, AlertChannelRepository channelRepo,
                           AlertRouteRepository routeRepo, AlertEventRepository eventRepo,
                           AlertNotificationRepository notifRepo, AlertSilenceRepository silenceRepo,
                           AlertRuleService ruleService, AlertLifecycleService lifecycleService,
                           AlertDispatchService dispatchService, ProjectScope projectScope) {
        this.ruleRepo = ruleRepo;
        this.channelRepo = channelRepo;
        this.routeRepo = routeRepo;
        this.eventRepo = eventRepo;
        this.notifRepo = notifRepo;
        this.silenceRepo = silenceRepo;
        this.ruleService = ruleService;
        this.lifecycleService = lifecycleService;
        this.dispatchService = dispatchService;
        this.projectScope = projectScope;
    }

    private long tenantId(ServerWebExchange exchange) {
        Long tid = exchange.getAttribute("tenantId");
        if (tid == null) throw new BizException("alert.tenant_required");
        return tid;
    }

    /**
     * 036 项目隔离：从 exchange 取当前项目并经 {@link ProjectScope} 校验成员归属。
     * 缺项目 → project.required；非成员 → project.forbidden。返回校验通过的 projectId。
     */
    private Long projectId(ServerWebExchange exchange) {
        Long tid = exchange.getAttribute("tenantId");
        Long uid = exchange.getAttribute("userId");
        Long pid = exchange.getAttribute("projectId");
        return projectScope.require(tid, uid, pid);
    }

    /**
     * 036 收口 T045b：by-id 端点的项目归属守卫。先校验当前用户在当前项目的成员归属
     * （{@link #projectId}），再断言目标实体确属该 (tenant, project)。防止用户携带自己
     * 有权的项目上下文，按 id 读/改/删他项目的告警资源。越权 → project.forbidden。
     */
    private void requireOwned(Long entTenantId, Long entProjectId, ServerWebExchange exchange) {
        long tid = tenantId(exchange);
        Long pid = projectId(exchange);
        if (entTenantId == null || entTenantId != tid || !pid.equals(entProjectId)) {
            throw new BizException("project.forbidden");
        }
    }

    // ─── Rules ─────────────────────────────────────────

    @GetMapping("/rules")
    public Mono<Map<String, Object>> listRules(ServerWebExchange exchange,
                                                @RequestParam(defaultValue = "0") int offset,
                                                @RequestParam(defaultValue = "20") int limit,
                                                @RequestParam(required = false) String signalSource,
                                                @RequestParam(required = false) Boolean enabled) {
        long tid = tenantId(exchange);
        Long pid = projectId(exchange);
        var list = ruleService.list(tid, pid, signalSource, enabled, offset, limit);
        return Mono.just(Map.of("code", 0, "data", Map.of("items", list, "total", ruleRepo.countByTenantIdAndProjectId(tid, pid))));
    }

    @GetMapping("/rules/{id}")
    public Mono<Map<String, Object>> getRule(ServerWebExchange exchange, @PathVariable Long id) {
        AlertRule rule = ruleService.get(id);
        requireOwned(rule.getTenantId(), rule.getProjectId(), exchange);
        return Mono.just(Map.of("code", 0, "data", rule));
    }

    @PostMapping("/rules")
    public Mono<Map<String, Object>> createRule(ServerWebExchange exchange, @RequestBody AlertRule rule) {
        rule.setTenantId(tenantId(exchange));
        rule.setProjectId(projectId(exchange));
        rule.setCreatedBy(1L);
        rule.setCreatedAt(LocalDateTime.now());
        return Mono.just(Map.of("code", 0, "data", ruleService.create(rule)));
    }

    @PatchMapping("/rules/{id}")
    public Mono<Map<String, Object>> updateRule(ServerWebExchange exchange, @PathVariable Long id, @RequestBody AlertRule patch) {
        AlertRule existing = ruleService.get(id);
        requireOwned(existing.getTenantId(), existing.getProjectId(), exchange);
        patch.setUpdatedBy(1L);
        return Mono.just(Map.of("code", 0, "data", ruleService.update(id, patch)));
    }

    @DeleteMapping("/rules/{id}")
    public Mono<Map<String, Object>> deleteRule(ServerWebExchange exchange, @PathVariable Long id) {
        AlertRule existing = ruleService.get(id);
        requireOwned(existing.getTenantId(), existing.getProjectId(), exchange);
        ruleService.delete(id);
        return Mono.just(Map.of("code", 0, "data", null));
    }

    // ─── Channels ──────────────────────────────────────

    @GetMapping("/channels")
    public Mono<Map<String, Object>> listChannels(ServerWebExchange exchange) {
        return Mono.just(Map.of("code", 0, "data",
                channelRepo.findByTenantIdAndProjectId(tenantId(exchange), projectId(exchange))));
    }

    @GetMapping("/channels/{id}")
    public Mono<Map<String, Object>> getChannel(ServerWebExchange exchange, @PathVariable Long id) {
        var c = channelRepo.findById(id).orElseThrow(() -> new BizException("alert.channel_not_found", id));
        requireOwned(c.getTenantId(), c.getProjectId(), exchange);
        return Mono.just(Map.of("code", 0, "data", c));
    }

    @PostMapping("/channels")
    public Mono<Map<String, Object>> createChannel(ServerWebExchange exchange, @RequestBody AlertChannel channel) {
        channel.setTenantId(tenantId(exchange));
        channel.setProjectId(projectId(exchange));
        channel.setCreatedBy(1L);
        return Mono.just(Map.of("code", 0, "data", channelRepo.save(channel)));
    }

    @PatchMapping("/channels/{id}")
    public Mono<Map<String, Object>> updateChannel(ServerWebExchange exchange, @PathVariable Long id, @RequestBody AlertChannel patch) {
        var c = channelRepo.findById(id).orElseThrow(() -> new BizException("alert.channel_not_found", id));
        requireOwned(c.getTenantId(), c.getProjectId(), exchange);
        if (patch.getName() != null) c.setName(patch.getName());
        if (patch.getType() != null) c.setType(patch.getType());
        if (patch.getConfigJson() != null) c.setConfigJson(patch.getConfigJson());
        if (patch.getRateLimitPerMin() != null) c.setRateLimitPerMin(patch.getRateLimitPerMin());
        if (patch.getEnabled() != null) c.setEnabled(patch.getEnabled());
        c.setUpdatedBy(1L);
        return Mono.just(Map.of("code", 0, "data", channelRepo.save(c)));
    }

    @DeleteMapping("/channels/{id}")
    public Mono<Map<String, Object>> deleteChannel(ServerWebExchange exchange, @PathVariable Long id) {
        var c = channelRepo.findById(id).orElseThrow(() -> new BizException("alert.channel_not_found", id));
        requireOwned(c.getTenantId(), c.getProjectId(), exchange);
        channelRepo.deleteById(id);
        return Mono.just(Map.of("code", 0, "data", null));
    }

    @PostMapping("/channels/{id}/test")
    public Mono<Map<String, Object>> testChannel(ServerWebExchange exchange, @PathVariable Long id) {
        var channel = channelRepo.findById(id).orElseThrow(() -> new BizException("alert.channel_not_found", id));
        requireOwned(channel.getTenantId(), channel.getProjectId(), exchange);
        AlertNotification notif = dispatchService.testSend(channel);
        return Mono.just(Map.of("code", 0, "data", Map.of(
                "outcome", "EXECUTED", "notification", notif,
                "status", notif.getStatus())));
    }

    // ─── Routes ────────────────────────────────────────

    @GetMapping("/routes")
    public Mono<Map<String, Object>> listRoutes(ServerWebExchange exchange) {
        return Mono.just(Map.of("code", 0, "data",
                routeRepo.findByTenantIdAndProjectId(tenantId(exchange), projectId(exchange))));
    }

    @PostMapping("/routes")
    public Mono<Map<String, Object>> createRoute(ServerWebExchange exchange, @RequestBody AlertRoute route) {
        route.setTenantId(tenantId(exchange));
        route.setProjectId(projectId(exchange));
        route.setCreatedBy(1L);
        return Mono.just(Map.of("code", 0, "data", routeRepo.save(route)));
    }

    @PatchMapping("/routes/{id}")
    public Mono<Map<String, Object>> updateRoute(ServerWebExchange exchange, @PathVariable Long id, @RequestBody AlertRoute patch) {
        var r = routeRepo.findById(id).orElseThrow(() -> new BizException("alert.rule_not_found", id));
        requireOwned(r.getTenantId(), r.getProjectId(), exchange);
        if (patch.getMatchJson() != null) r.setMatchJson(patch.getMatchJson());
        if (patch.getChannelIds() != null) r.setChannelIds(patch.getChannelIds());
        if (patch.getSortOrder() != null) r.setSortOrder(patch.getSortOrder());
        if (patch.getEnabled() != null) r.setEnabled(patch.getEnabled());
        r.setUpdatedBy(1L);
        return Mono.just(Map.of("code", 0, "data", routeRepo.save(r)));
    }

    @DeleteMapping("/routes/{id}")
    public Mono<Map<String, Object>> deleteRoute(ServerWebExchange exchange, @PathVariable Long id) {
        var r = routeRepo.findById(id).orElseThrow(() -> new BizException("alert.rule_not_found", id));
        requireOwned(r.getTenantId(), r.getProjectId(), exchange);
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
        Long pid = projectId(exchange);
        List<AlertEvent> events;
        int total;
        if (state != null && !state.isBlank()) {
            events = eventRepo.findByTenantIdAndProjectIdAndState(tid, pid, state, offset, limit);
            total = eventRepo.countByTenantIdAndProjectIdAndState(tid, pid, state);
        } else {
            events = eventRepo.findByTenantIdAndProjectId(tid, pid, offset, limit);
            total = eventRepo.countByTenantIdAndProjectId(tid, pid);
        }
        return Mono.just(Map.of("code", 0, "data", Map.of("items", events, "total", total)));
    }

    @GetMapping("/events/{id}")
    public Mono<Map<String, Object>> getEvent(ServerWebExchange exchange, @PathVariable Long id) {
        var e = eventRepo.findById(id).orElseThrow(() -> new BizException("alert.event_not_found", id));
        requireOwned(e.getTenantId(), e.getProjectId(), exchange);
        var notifications = notifRepo.findByEventId(e.getTenantId(), id);
        return Mono.just(Map.of("code", 0, "data", Map.of("event", e, "notifications", notifications)));
    }

    @PostMapping("/events/{id}/ack")
    public Mono<Map<String, Object>> ackEvent(ServerWebExchange exchange, @PathVariable Long id, @RequestBody Map<String, Object> body) {
        var target = eventRepo.findById(id).orElseThrow(() -> new BizException("alert.event_not_found", id));
        requireOwned(target.getTenantId(), target.getProjectId(), exchange);
        Long ackedBy = body.get("ackedBy") instanceof Number n ? n.longValue() : 1L;
        if (!lifecycleService.ack(id, ackedBy)) {
            throw new BizException("alert.event_not_ackable", id, "current state not FIRING");
        }
        var e = eventRepo.findById(id).orElseThrow();
        return Mono.just(Map.of("code", 0, "data", e));
    }

    @GetMapping("/events/{id}/notifications")
    public Mono<Map<String, Object>> getEventNotifications(ServerWebExchange exchange, @PathVariable Long id) {
        var e = eventRepo.findById(id).orElseThrow(() -> new BizException("alert.event_not_found", id));
        requireOwned(e.getTenantId(), e.getProjectId(), exchange);
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
