package com.dataweave.api.interfaces;

import com.dataweave.api.infrastructure.ApiResponse;
import com.dataweave.api.infrastructure.Locales;
import com.dataweave.api.infrastructure.TenantContext;
import com.dataweave.master.application.ActionRequest;
import com.dataweave.master.application.GateResult;
import com.dataweave.master.application.GatedActionService;
import com.dataweave.master.application.asset.AssetDtos;
import com.dataweave.master.application.asset.AssetLineageAssembler;
import com.dataweave.master.application.asset.MetricListingService;
import com.dataweave.master.i18n.BizException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 指标市场 REST（/api/marketplace/*）。复用现有 metrics 定义上架；详情含血缘（降级安全）+ 认证。
 * 写经闸门（ASSET_WRITE / METRIC_CERTIFY，L2 认证可能 PENDING_APPROVAL）。
 */
@RestController
@RequestMapping("/api/marketplace")
public class MetricMarketplaceController {

    private final MetricListingService listingService;
    private final AssetLineageAssembler lineageAssembler;
    private final GatedActionService gatedActionService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MetricMarketplaceController(MetricListingService listingService,
                                       AssetLineageAssembler lineageAssembler,
                                       GatedActionService gatedActionService) {
        this.listingService = listingService;
        this.lineageAssembler = lineageAssembler;
        this.gatedActionService = gatedActionService;
    }

    /** 详情 + 血缘（degraded 安全）的组合响应。 */
    public record MarketplaceDetail(AssetDtos.ListingDetail detail, AssetDtos.LineageEntryView lineage) {
    }

    // ─── 读 ────────────────────────────────────────────────────

    @GetMapping("/metrics")
    public ApiResponse<AssetDtos.ListingSearchResult> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String certification,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "1") Long projectId) {
        return ApiResponse.ok(listingService.search(requireTenant(), projectId, keyword, certification, page, size));
    }

    @GetMapping("/metrics/{id}")
    public ApiResponse<MarketplaceDetail> detail(@PathVariable Long id,
                                                 @RequestParam(defaultValue = "1") Long projectId) {
        long tenantId = requireTenant();
        AssetDtos.ListingDetail detail = listingService.getDetail(tenantId, id);
        AssetDtos.LineageEntryView lineage = lineageAssembler.assembleMetric(
                tenantId, projectId, detail.metricType(), detail.metricId(), detail.metricCode());
        return ApiResponse.ok(new MarketplaceDetail(detail, lineage));
    }

    // ─── 写（过闸门）────────────────────────────────────────────

    @PostMapping("/metrics")
    public ApiResponse<GateResult> list(@RequestBody Map<String, Object> body,
                                        @RequestParam(defaultValue = "1") Long projectId,
                                        ServerWebExchange exchange) {
        long tenantId = requireTenant();
        Map<String, Object> cmd = gateBase("metric.list", tenantId, projectId, requireUser());
        cmd.put("metric", body);
        return submit("ASSET_WRITE", "METRIC_LISTING", null, cmd,
                "上架指标 " + body.getOrDefault("metricCode", body.getOrDefault("metricId", "")), exchange);
    }

    @PostMapping("/metrics/{id}/certify")
    public ApiResponse<GateResult> certify(@PathVariable Long id,
                                           @RequestParam(defaultValue = "1") Long projectId,
                                           ServerWebExchange exchange) {
        long tenantId = requireTenant();
        Map<String, Object> cmd = gateBase("metric.certify", tenantId, projectId, requireUser());
        cmd.put("id", id);
        return submit("METRIC_CERTIFY", "METRIC_LISTING", String.valueOf(id), cmd, "认证指标 #" + id, exchange);
    }

    @DeleteMapping("/metrics/{id}")
    public ApiResponse<GateResult> delist(@PathVariable Long id,
                                          @RequestParam(defaultValue = "1") Long projectId,
                                          ServerWebExchange exchange) {
        long tenantId = requireTenant();
        Map<String, Object> cmd = gateBase("metric.delist", tenantId, projectId, requireUser());
        cmd.put("id", id);
        return submit("ASSET_WRITE", "METRIC_LISTING", String.valueOf(id), cmd, "下架指标 #" + id, exchange);
    }

    @PostMapping("/metrics/{id}/reuse")
    public ApiResponse<GateResult> reuse(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                         @RequestParam(defaultValue = "1") Long projectId,
                                         ServerWebExchange exchange) {
        long tenantId = requireTenant();
        Map<String, Object> cmd = gateBase("metric.reuse", tenantId, projectId, requireUser());
        cmd.put("id", id);
        cmd.put("consumerType", body.getOrDefault("consumerType", "METRIC"));
        cmd.put("consumerRef", body.get("consumerRef"));
        return submit("ASSET_WRITE", "METRIC_LISTING", String.valueOf(id), cmd,
                "复用指标 #" + id + " ← " + body.get("consumerRef"), exchange);
    }

    // ─── helpers ───────────────────────────────────────────────

    private ApiResponse<GateResult> submit(String tool, String targetType, String targetId,
                                           Map<String, Object> cmd, String summary, ServerWebExchange exchange) {
        ActionRequest req = ActionRequest.builder()
                .toolName(tool).actionType(tool)
                .targetType(targetType).targetId(targetId)
                .command(objectMapper.writeValueAsString(cmd))
                .actor(String.valueOf(TenantContext.userId())).actorSource("UI")
                .summary(summary)
                .build();
        return ApiResponse.ok(gatedActionService.submit(req, Locales.uiLocale(exchange.getRequest().getHeaders())));
    }

    private Map<String, Object> gateBase(String op, long tenantId, long projectId, long userId) {
        Map<String, Object> cmd = new LinkedHashMap<>();
        cmd.put("op", op);
        cmd.put("tenantId", tenantId);
        cmd.put("projectId", projectId);
        cmd.put("userId", userId);
        return cmd;
    }

    private long requireTenant() {
        Long t = TenantContext.tenantId();
        if (t == null) throw new BizException("catalog.tenant_required").withHttpStatus(401);
        return t;
    }

    private long requireUser() {
        Long u = TenantContext.userId();
        return u == null ? 0L : u;
    }
}
