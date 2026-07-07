package com.dataweave.api.interfaces;

import java.util.List;

import com.dataweave.api.infrastructure.ApiResponse;
import com.dataweave.api.infrastructure.TenantContext;
import com.dataweave.master.application.ProjectScope;
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
 * 053 血缘 AI Agent 配置 REST（契约 config-api.md）。
 * 基址 /api/lineage/agent-config（JWT + X-Project-Id/?projectId=，经 {@link ProjectScope#require} 成员校验）。
 * GET 脱敏返回；PUT 加密 upsert；POST /test 发一次最小探活外呼（不落库、不落日志明文）。
 */
@RestController
@RequestMapping("/api/lineage/agent-config")
public class LineageAgentConfigController {

    private static final int DEFAULT_TIMEOUT_MS = 30000;
    private static final int DEFAULT_RATE_LIMIT = 60;
    private static final int DEFAULT_MAX_COLUMNS = 2000;

    private final AgentLineageConfigService configService;
    private final LlmAgentClient client;
    private final ProjectScope projectScope;
    private final AgentConfigRepository agentConfigRepository;

    public LineageAgentConfigController(AgentLineageConfigService configService,
                                        LlmAgentClient client,
                                        ProjectScope projectScope,
                                        AgentConfigRepository agentConfigRepository) {
        this.configService = configService;
        this.client = client;
        this.projectScope = projectScope;
        this.agentConfigRepository = agentConfigRepository;
    }

    /** projectId 优先取请求显式参数 → 回退 TenantContext → ProjectScope.require 成员校验。 */
    private long resolveProjectId(Long requestProjectId) {
        Long pid = requestProjectId != null ? requestProjectId : TenantContext.projectId();
        return projectScope.require(TenantContext.tenantId(), TenantContext.userId(), pid);
    }

    @GetMapping
    public ApiResponse<AgentConfigVo> get(@RequestParam(required = false) Long projectId) {
        long pid = resolveProjectId(projectId);
        return ApiResponse.ok(configService.get(TenantContext.tenantId(), pid).orElse(null));
    }

    @PutMapping
    public ApiResponse<AgentConfigVo> put(@RequestBody UpsertRequest req,
                                          @RequestParam(required = false) Long projectId) {
        long pid = resolveProjectId(projectId);
        return ApiResponse.ok(configService.upsert(TenantContext.tenantId(), pid, TenantContext.userId(), req));
    }

    /** 用当前（或请求体覆盖）配置发一次最小探活外呼；不落库、不落日志明文（FR-020）。 */
    @PostMapping("/test")
    public ApiResponse<TestResult> test(@RequestBody UpsertRequest req,
                                        @RequestParam(required = false) Long projectId) {
        long tenantId = TenantContext.tenantId();
        long pid = resolveProjectId(projectId);
        long userId = TenantContext.userId();

        LineageAgentConfig existing = configService.getActive(tenantId, pid).orElse(null);
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
                null, tenantId, pid, protocol, baseUrl, model, apiKeyEnc, true,
                timeoutMs, rateLimitPerMin, maxColumns, userId, userId, null, null, 0, 0);
        LlmAgentClient.CallResult call = client.test(testCfg);
        boolean ok = call.error() == null;
        return ApiResponse.ok(new TestResult(ok, call.latencyMs(), ok ? null : call.error()));
    }

    /** POST /test 响应。note 仅在失败时给技术原因摘要（脱敏，无明文 key）；前端按 ok 做 i18n 文案。 */
    public record TestResult(boolean ok, long latencyMs, String note) {}

    /**
     * T034/US4：审计只读端点（FR-021）。查询当前项目最近 N 条外呼审计记录。
     * 结果脱敏——不含明文 key/脚本内容。
     */
    @GetMapping("/calls")
    public ApiResponse<List<CallRecord>> calls(@RequestParam(required = false) Long taskDefId,
                                               @RequestParam(required = false) Long projectId,
                                               @RequestParam(defaultValue = "50") int limit) {
        long pid = resolveProjectId(projectId);
        int capped = Math.max(1, Math.min(limit, 200));
        return ApiResponse.ok(agentConfigRepository.findCalls(
                TenantContext.tenantId(), pid, taskDefId, capped));
    }
}
