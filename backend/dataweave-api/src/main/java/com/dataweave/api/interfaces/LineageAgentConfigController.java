package com.dataweave.api.interfaces;

import java.util.List;

import com.dataweave.api.infrastructure.ApiResponse;
import com.dataweave.api.infrastructure.TenantContext;
import com.dataweave.master.application.ProjectRoleService;
import com.dataweave.master.application.lineage.agent.AgentLineageConfigService;
import com.dataweave.master.application.lineage.agent.AgentLineageConfigService.AgentConfigVo;
import com.dataweave.master.application.lineage.agent.AgentLineageConfigService.UpsertRequest;
import com.dataweave.master.application.lineage.agent.LlmAgentClient;
import com.dataweave.master.domain.lineage.LineageAgentConfig;
import com.dataweave.master.i18n.BizException;
import com.dataweave.master.infrastructure.lineage.AgentConfigRepository;
import com.dataweave.master.infrastructure.lineage.AgentConfigRepository.CallRecord;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 057 全局 AI Agent 配置 REST（契约 contracts/system-settings-api.md）。
 * 基址 /api/settings/agent-config（JWT 鉴权 + 租户管理员校验；租户级全局单例，去 projectId）。
 * 鉴权：JwtAuthFilter 校验 JWT + {@link ProjectRoleService#requireTenantAdmin} 校验用户在任意项目持有 ADMIN 角色。
 * GET 脱敏返回；PUT 加密 upsert；POST /test 发一次最小探活外呼（不落库、不落日志明文）。
 */
@RestController
@RequestMapping("/api/settings/agent-config")
public class LineageAgentConfigController {

    private static final int DEFAULT_TIMEOUT_MS = 30000;
    private static final int DEFAULT_RATE_LIMIT = 60;
    private static final int DEFAULT_MAX_COLUMNS = 2000;

    private final AgentLineageConfigService configService;
    private final LlmAgentClient client;
    private final AgentConfigRepository agentConfigRepository;
    private final ProjectRoleService projectRoleService;

    public LineageAgentConfigController(AgentLineageConfigService configService,
                                        LlmAgentClient client,
                                        AgentConfigRepository agentConfigRepository,
                                        ProjectRoleService projectRoleService) {
        this.configService = configService;
        this.client = client;
        this.agentConfigRepository = agentConfigRepository;
        this.projectRoleService = projectRoleService;
    }

    @GetMapping
    public ApiResponse<AgentConfigVo> get() {
        projectRoleService.requireTenantAdmin(TenantContext.tenantId(), TenantContext.userId());
        return ApiResponse.ok(configService.get(TenantContext.tenantId()).orElse(null));
    }

    @PutMapping
    public ApiResponse<AgentConfigVo> put(@RequestBody UpsertRequest req) {
        projectRoleService.requireTenantAdmin(TenantContext.tenantId(), TenantContext.userId());
        return ApiResponse.ok(configService.upsert(TenantContext.tenantId(), TenantContext.userId(), req));
    }

    /** 用当前（或请求体覆盖）配置发一次最小探活外呼；不落库、不落日志明文（FR-020）。 */
    @PostMapping("/test")
    public ApiResponse<TestResult> test(@RequestBody UpsertRequest req) {
        projectRoleService.requireTenantAdmin(TenantContext.tenantId(), TenantContext.userId());
        long tenantId = TenantContext.tenantId();
        long userId = TenantContext.userId();

        LineageAgentConfig existing = configService.getActive(tenantId).orElse(null);
        String protocol = req.protocol() != null ? req.protocol()
                : (existing != null ? existing.protocol() : null);
        String baseUrl = req.baseUrl() != null ? req.baseUrl()
                : (existing != null ? existing.baseUrl() : null);
        String model = req.model() != null ? req.model()
                : (existing != null ? existing.model() : null);
        if (protocol == null || baseUrl == null || model == null) {
            throw new BizException("lineage_agent.config_incomplete");
        }
        String apiKeyEnc = (req.apiKey() != null && !req.apiKey().isEmpty())
                ? configService.encryptApiKey(req.apiKey())
                : (existing != null ? existing.apiKeyEnc() : null);
        int timeoutMs = req.timeoutMs() != null ? req.timeoutMs()
                : (existing != null ? existing.timeoutMs() : DEFAULT_TIMEOUT_MS);
        int rateLimitPerMin = req.rateLimitPerMin() != null ? req.rateLimitPerMin()
                : (existing != null ? existing.rateLimitPerMin() : DEFAULT_RATE_LIMIT);
        int maxColumns = req.maxColumns() != null ? req.maxColumns()
                : (existing != null ? existing.maxColumns() : DEFAULT_MAX_COLUMNS);

        LineageAgentConfig testCfg = new LineageAgentConfig(
                null, tenantId, protocol, baseUrl, model, apiKeyEnc, true, false,
                timeoutMs, rateLimitPerMin, maxColumns, userId, userId, null, null, 0, 0);
        LlmAgentClient.CallResult call = client.test(testCfg);
        boolean ok = call.error() == null;
        return ApiResponse.ok(new TestResult(ok, call.latencyMs(), ok ? null : call.error()));
    }

    /** POST /test 响应。note 仅在失败时给技术原因摘要（脱敏，无明文 key）；前端按 ok 做 i18n 文案。 */
    public record TestResult(boolean ok, long latencyMs, String note) {}

    /**
     * T034/US4：审计只读端点（FR-021）。057：全局配置 → 跨项目查询最近 N 条外呼审计。
     * 结果脱敏——不含明文 key/脚本内容；CallRecord 含 projectId 供溯源展示。
     */
    @GetMapping("/calls")
    public ApiResponse<List<CallRecord>> calls(@RequestParam(required = false) Long taskDefId,
                                               @RequestParam(defaultValue = "50") int limit) {
        projectRoleService.requireTenantAdmin(TenantContext.tenantId(), TenantContext.userId());
        int capped = Math.max(1, Math.min(limit, 200));
        return ApiResponse.ok(agentConfigRepository.findCalls(
                TenantContext.tenantId(), taskDefId, capped));
    }
}
