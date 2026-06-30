package com.dataweave.alert.interfaces;

import com.dataweave.alert.application.EventCenterService;
import com.dataweave.alert.domain.EventSubscription;
import com.dataweave.master.i18n.BizException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 事件中心 REST controller（027）：/api/events/*（WebFlux + 租户隔离 via exchange 属性）。
 */
@RestController
@RequestMapping("/api/events")
public class EventCenterController {

    private final EventCenterService service;

    public EventCenterController(EventCenterService service) {
        this.service = service;
    }

    private long tenantId(ServerWebExchange exchange) {
        Long tid = exchange.getAttribute("tenantId");
        if (tid == null) throw new BizException("alert.tenant_required");
        return tid;
    }

    // ── 事件查询 ──
    @GetMapping
    public Mono<Map<String, Object>> listEvents(ServerWebExchange exchange,
                                                @RequestParam(required = false) String type,
                                                @RequestParam(required = false) String severity,
                                                @RequestParam(required = false) String refKind,
                                                @RequestParam(required = false) String refId,
                                                @RequestParam(required = false) String from,
                                                @RequestParam(required = false) String to,
                                                @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        long tid = tenantId(exchange);
        int offset = Math.max(0, (page - 1) * size);
        var data = service.query(tid, type, severity, refKind, refId,
                parse(from), parse(to), offset, size);
        return Mono.just(Map.of("code", 0, "data", data));
    }

    // ── 订阅 ──
    @GetMapping("/subscriptions")
    public Mono<Map<String, Object>> listSubscriptions(ServerWebExchange exchange) {
        return Mono.just(Map.of("code", 0, "data", service.listSubscriptions(tenantId(exchange))));
    }

    @PostMapping("/subscriptions")
    public Mono<Map<String, Object>> subscribe(ServerWebExchange exchange, @RequestBody EventSubscription sub) {
        sub.setTenantId(tenantId(exchange));
        if (sub.getChannelId() == null) throw new BizException("event.channel_required");
        if (sub.getEnabled() == null) sub.setEnabled(1);
        sub.setCreatedAt(LocalDateTime.now());
        return Mono.just(Map.of("code", 0, "data", service.subscribe(sub)));
    }

    @DeleteMapping("/subscriptions/{id}")
    public Mono<Map<String, Object>> unsubscribe(@PathVariable Long id) {
        service.unsubscribe(id);
        return Mono.just(Map.of("code", 0, "data", Map.of("ok", true)));
    }

    private static LocalDateTime parse(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return LocalDateTime.parse(iso);
        } catch (Exception e) {
            throw new BizException("event.bad_time_format", iso);
        }
    }
}
