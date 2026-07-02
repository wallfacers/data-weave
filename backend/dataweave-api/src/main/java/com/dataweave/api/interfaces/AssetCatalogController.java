package com.dataweave.api.interfaces;

import com.dataweave.api.infrastructure.ApiResponse;
import com.dataweave.api.infrastructure.Locales;
import com.dataweave.api.infrastructure.TenantContext;
import com.dataweave.master.application.ActionRequest;
import com.dataweave.master.application.GateResult;
import com.dataweave.master.application.GatedActionService;
import com.dataweave.master.application.ProjectScope;
import com.dataweave.master.application.asset.AssetCatalogService;
import com.dataweave.master.application.asset.AssetDtos;
import com.dataweave.master.application.asset.AssetLineageAssembler;
import com.dataweave.master.application.asset.AssetQualityBadgeAssembler;
import com.dataweave.master.application.asset.AssetSearchService;
import com.dataweave.master.application.asset.AssetSubscriptionService;
import com.dataweave.master.domain.asset.AssetSubscription;
import com.dataweave.master.domain.asset.DataAsset;
import com.dataweave.master.i18n.BizException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 资产目录 REST（/api/catalog/*）。读直调 master service；写经
 * {@link ActionRequest} → {@link GatedActionService}（PolicyEngine 闸门 + agent_action 审计，零旁路 FR-009）。
 *
 * <p>036 项目隔离：projectId 默认从 TenantContext 取，经 ProjectScope.require 成员校验；
 * 缺身份 → catalog.tenant_required / project.required。
 */
@RestController
@RequestMapping("/api/catalog")
public class AssetCatalogController {

    private final AssetCatalogService catalogService;
    private final AssetSearchService searchService;
    private final AssetSubscriptionService subscriptionService;
    private final AssetLineageAssembler lineageAssembler;
    private final AssetQualityBadgeAssembler qualityAssembler;
    private final GatedActionService gatedActionService;
    private final ProjectScope projectScope;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AssetCatalogController(AssetCatalogService catalogService,
                                  AssetSearchService searchService,
                                  AssetSubscriptionService subscriptionService,
                                  AssetLineageAssembler lineageAssembler,
                                  AssetQualityBadgeAssembler qualityAssembler,
                                  GatedActionService gatedActionService,
                                  ProjectScope projectScope) {
        this.catalogService = catalogService;
        this.searchService = searchService;
        this.subscriptionService = subscriptionService;
        this.lineageAssembler = lineageAssembler;
        this.qualityAssembler = qualityAssembler;
        this.gatedActionService = gatedActionService;
        this.projectScope = projectScope;
    }

    // ─── 读 ────────────────────────────────────────────────────

    @GetMapping("/assets")
    public ApiResponse<AssetDtos.SearchResult> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String owner,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String sensitivity,
            @RequestParam(required = false) Double qualityMin,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long projectId) {
        long tenantId = requireTenant();
        long userId = requireUser();
        AssetDtos.SearchQuery q = new AssetDtos.SearchQuery(keyword, type, owner, tag, sensitivity, qualityMin, page, size);
        return ApiResponse.ok(searchService.search(tenantId, resolveProjectId(projectId), userId, q));
    }

    @GetMapping("/assets/{id}")
    public ApiResponse<AssetDtos.AssetDetail> detail(@PathVariable Long id) {
        return ApiResponse.ok(catalogService.getDetail(requireTenant(), id, requireUser()));
    }

    /** 资产血缘入口（代理 LineageQueryService）；neo4j 不可达 → degraded（前端隐藏入口，不报错）。 */
    @GetMapping("/assets/{id}/lineage")
    public ApiResponse<AssetDtos.LineageEntryView> lineage(@PathVariable Long id,
                                                            @RequestParam(required = false) Long projectId) {
        long tenantId = requireTenant();
        DataAsset a = catalogService.requireVisible(tenantId, id, requireUser());
        return ApiResponse.ok(lineageAssembler.assemble(tenantId, resolveProjectId(projectId), a.getLineageTableRef()));
    }

    /** 质量徽章（消费份2 quality_scorecard）；不可达 → degraded。 */
    @GetMapping("/assets/{id}/quality")
    public ApiResponse<AssetDtos.QualityBadgeView> quality(@PathVariable Long id,
                                                           @RequestParam(required = false) Long projectId) {
        long tenantId = requireTenant();
        DataAsset a = catalogService.requireVisible(tenantId, id, requireUser());
        return ApiResponse.ok(qualityAssembler.assemble(tenantId, resolveProjectId(projectId), a.getQualifiedName()));
    }

    @GetMapping("/subscriptions")
    public ApiResponse<List<AssetSubscription>> subscriptions() {
        return ApiResponse.ok(subscriptionService.listForUser(requireTenant(), requireUser()));
    }

    // ─── 写（过闸门）────────────────────────────────────────────

    @PostMapping("/assets")
    public ApiResponse<GateResult> create(@RequestBody Map<String, Object> body,
                                          @RequestParam(required = false) Long projectId,
                                          ServerWebExchange exchange) {
        long tenantId = requireTenant();
        long userId = requireUser();
        Map<String, Object> cmd = gateBase("asset.create", tenantId, resolveProjectId(projectId), userId);
        cmd.put("asset", body);
        return submit("ASSET_WRITE", "ASSET", null, cmd,
                "编目资产 " + body.getOrDefault("qualifiedName", ""), exchange);
    }

    @PatchMapping("/assets/{id}")
    public ApiResponse<GateResult> update(@PathVariable Long id, @RequestBody Map<String, Object> patch,
                                          @RequestParam(required = false) Long projectId,
                                          ServerWebExchange exchange) {
        long tenantId = requireTenant();
        Map<String, Object> cmd = gateBase("asset.update", tenantId, resolveProjectId(projectId), requireUser());
        cmd.put("id", id);
        cmd.put("patch", patch);
        return submit("ASSET_WRITE", "ASSET", String.valueOf(id), cmd, "更新资产 #" + id, exchange);
    }

    @DeleteMapping("/assets/{id}")
    public ApiResponse<GateResult> retire(@PathVariable Long id,
                                          @RequestParam(required = false) Long projectId,
                                          ServerWebExchange exchange) {
        long tenantId = requireTenant();
        Map<String, Object> cmd = gateBase("asset.retire", tenantId, resolveProjectId(projectId), requireUser());
        cmd.put("id", id);
        return submit("ASSET_WRITE", "ASSET", String.valueOf(id), cmd, "下线资产 #" + id, exchange);
    }

    /** schema 对账（场景8）：底层表删/改名 → STALE；恢复 → ACTIVE。 */
    @PostMapping("/assets/{id}/reconcile")
    public ApiResponse<GateResult> reconcile(@PathVariable Long id,
                                             @RequestParam(required = false) Long projectId,
                                             ServerWebExchange exchange) {
        long tenantId = requireTenant();
        Map<String, Object> cmd = gateBase("asset.reconcile", tenantId, resolveProjectId(projectId), requireUser());
        cmd.put("id", id);
        return submit("ASSET_WRITE", "ASSET", String.valueOf(id), cmd, "资产对账 #" + id, exchange);
    }

    @PostMapping("/subscriptions")
    public ApiResponse<GateResult> subscribe(@RequestBody Map<String, Object> body,
                                             @RequestParam(required = false) Long projectId,
                                             ServerWebExchange exchange) {
        long tenantId = requireTenant();
        Map<String, Object> cmd = gateBase("subscribe", tenantId, resolveProjectId(projectId), requireUser());
        cmd.put("targetType", body.get("targetType"));
        cmd.put("targetId", body.get("targetId"));
        cmd.put("changeFilter", body.get("changeFilter"));
        return submit("ASSET_SUBSCRIBE", "SUBSCRIPTION", null, cmd, "订阅 " + body.get("targetType") + " #" + body.get("targetId"), exchange);
    }

    @DeleteMapping("/subscriptions/{id}")
    public ApiResponse<GateResult> unsubscribe(@PathVariable Long id,
                                               @RequestParam(required = false) Long projectId,
                                               ServerWebExchange exchange) {
        long tenantId = requireTenant();
        Map<String, Object> cmd = gateBase("unsubscribe", tenantId, resolveProjectId(projectId), requireUser());
        cmd.put("id", id);
        return submit("ASSET_SUBSCRIBE", "SUBSCRIPTION", String.valueOf(id), cmd, "退订 #" + id, exchange);
    }

    // ─── helpers ───────────────────────────────────────────────

    private Long resolveProjectId(Long requestProjectId) {
        Long pid = requestProjectId != null ? requestProjectId : TenantContext.projectId();
        return projectScope.require(TenantContext.tenantId(), TenantContext.userId(), pid);
    }

    private ApiResponse<GateResult> submit(String tool, String targetType, String targetId,
                                           Map<String, Object> cmd, String summary, ServerWebExchange exchange) {
        ActionRequest req = ActionRequest.builder()
                .toolName(tool).actionType(tool)
                .targetType(targetType).targetId(targetId)
                .command(writeJson(cmd))
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

    private String writeJson(Map<String, Object> cmd) {
        return objectMapper.writeValueAsString(cmd);
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
