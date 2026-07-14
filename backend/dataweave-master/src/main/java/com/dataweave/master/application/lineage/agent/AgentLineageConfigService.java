package com.dataweave.master.application.lineage.agent;

import java.util.Optional;
import java.util.Set;

import com.dataweave.master.application.DatasourceEncryptor;
import com.dataweave.master.domain.lineage.LineageAgentConfig;
import com.dataweave.master.i18n.BizException;
import com.dataweave.master.infrastructure.lineage.AgentConfigRepository;
import org.springframework.stereotype.Service;

/**
 * 053 云 AI Agent 配置服务：CRUD + 凭据加密/脱敏 + 完整性校验 + 启用判定（FR-019/FR-020）。
 * apiKey 缺省=不改（PATCH null vs 缺失语义，记忆）：PUT 时 apiKey 为 null/空 → 保留旧 api_key_enc。
 */
@Service
public class AgentLineageConfigService {

    private static final Set<String> PROTOCOLS = Set.of("ANTHROPIC", "OPENAI");
    private static final int DEFAULT_TIMEOUT_MS = 30000;
    private static final int DEFAULT_RATE_LIMIT = 60;
    private static final int DEFAULT_MAX_COLUMNS = 2000;

    private final AgentConfigRepository repo;
    private final DatasourceEncryptor encryptor;

    public AgentLineageConfigService(AgentConfigRepository repo, DatasourceEncryptor encryptor) {
        this.repo = repo;
        this.encryptor = encryptor;
    }

    /** 057：返回前端的脱敏 VO（apiKeyMasked = sk-…末4位，FR-020）。无配置返回 empty。 */
    public Optional<AgentConfigVo> get(long tenantId) {
        return repo.findActive(tenantId).map(this::toVo);
    }

    /** 057：给 enricher/client：取含 apiKeyEnc 的 domain（解密在 client 内即用即弃）。 */
    public Optional<LineageAgentConfig> getActive(long tenantId) {
        return repo.findActive(tenantId);
    }

    /** 057：全局配置是否启用（血缘富化用途）。 */
    public boolean isEnabledFor(long tenantId) {
        return repo.findActive(tenantId).map(LineageAgentConfig::enabled).orElse(false);
    }

    /** 067：智能运维用途是否启用（独立于 enabled，FR-012）。无生效配置视为未启用。 */
    public boolean isOpsEnabledFor(long tenantId) {
        return repo.findActive(tenantId).map(LineageAgentConfig::opsEnabled).orElse(false);
    }

    /** 067：切换智能运维开关；无生效配置（未配置协议/端点）时抛错，不能凭空开启。 */
    public void setOpsEnabled(long tenantId, boolean opsEnabled) {
        int rows = repo.updateOpsEnabled(tenantId, opsEnabled);
        if (rows == 0) {
            throw new BizException("lineage_agent.config_incomplete");
        }
    }

    /** 057：创建或更新（租户级全局单例，upsert）；返回脱敏 VO。 */
    public AgentConfigVo upsert(long tenantId, Long userId, UpsertRequest req) {
        validate(req);
        boolean enabled = req.enabled() != null && req.enabled();
        int timeoutMs = req.timeoutMs() != null ? req.timeoutMs() : DEFAULT_TIMEOUT_MS;
        int rateLimitPerMin = req.rateLimitPerMin() != null ? req.rateLimitPerMin() : DEFAULT_RATE_LIMIT;
        int maxColumns = req.maxColumns() != null ? req.maxColumns() : DEFAULT_MAX_COLUMNS;

        // null → update 走 COALESCE 保留旧密文；insert 时 null = 免鉴权网关
        String apiKeyEnc = (req.apiKey() != null && !req.apiKey().isEmpty())
                ? encryptor.encrypt(req.apiKey()) : null;

        Optional<LineageAgentConfig> existing = repo.findActive(tenantId);
        if (existing.isPresent()) {
            repo.update(existing.get().id(), req.protocol(), req.baseUrl(), req.model(), apiKeyEnc,
                    enabled, timeoutMs, rateLimitPerMin, maxColumns, userId);
        } else {
            repo.insert(tenantId, req.protocol(), req.baseUrl(), req.model(), apiKeyEnc,
                    enabled, timeoutMs, rateLimitPerMin, maxColumns, userId);
        }
        return repo.findActive(tenantId).map(this::toVo)
                .orElseThrow(() -> new IllegalStateException("agent config upsert produced no row"));
    }

    private void validate(UpsertRequest req) {
        if (req.protocol() == null || !PROTOCOLS.contains(req.protocol())) {
            throw new BizException("lineage_agent.protocol_invalid", String.valueOf(req.protocol()));
        }
        boolean enabled = req.enabled() != null && req.enabled();
        boolean baseUrlOk = req.baseUrl() != null
                && (req.baseUrl().startsWith("http://") || req.baseUrl().startsWith("https://"));
        if (enabled && (!baseUrlOk || req.model() == null || req.model().isBlank())) {
            throw new BizException("lineage_agent.config_incomplete");
        }
        // 非空但非 http(s) 的 baseUrl 一律视为不完整
        if (!baseUrlOk && req.baseUrl() != null && !req.baseUrl().isBlank()) {
            throw new BizException("lineage_agent.config_incomplete");
        }
    }

    private AgentConfigVo toVo(LineageAgentConfig c) {
        return new AgentConfigVo(c.id(), c.protocol(), c.baseUrl(), c.model(),
                maskApiKey(c.apiKeyEnc()), c.enabled(), c.timeoutMs(), c.rateLimitPerMin(), c.maxColumns());
    }

    private String maskApiKey(String apiKeyEnc) {
        if (apiKeyEnc == null || apiKeyEnc.isEmpty()) return null;
        try {
            String plain = encryptor.decrypt(apiKeyEnc);
            if (plain == null || plain.length() < 4) return "sk-…";
            return "sk-…" + plain.substring(plain.length() - 4);
        } catch (Exception e) {
            return "sk-…";  // 解密失败不外抛，返回无尾缀脱敏
        }
    }

    /** 加密 apiKey 明文（test 端点构造临时配置用，不落库）。null/空返回 null。 */
    public String encryptApiKey(String plain) {
        return (plain == null || plain.isEmpty()) ? null : encryptor.encrypt(plain);
    }

    /** GET 脱敏响应（apiKeyMasked=null 表示未配置 key）。 */
    public record AgentConfigVo(Long id, String protocol, String baseUrl, String model,
                                String apiKeyMasked, boolean enabled,
                                int timeoutMs, int rateLimitPerMin, int maxColumns) {}

    /** PUT 请求体。apiKey=null/空=不改（保留旧密文）；enabled/timeoutMs/rateLimitPerMin/maxColumns=null=默认值。 */
    public record UpsertRequest(String protocol, String baseUrl, String model, String apiKey,
                                Boolean enabled, Integer timeoutMs, Integer rateLimitPerMin, Integer maxColumns) {}
}
