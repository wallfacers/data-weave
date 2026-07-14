package com.dataweave.master.domain.lineage;

import java.time.LocalDateTime;

/**
 * 057 全局 AI Agent 抽取配置（lineage_agent_config 表）。租户级全局单例（每租户一条），默认 enabled=false（FR-019）。
 * 053 原按项目隔离，057 提升为全局所有项目共用一份。
 * api_key_enc 经 {@code DatasourceEncryptor} 加密，明文绝不入库/日志（FR-020）；
 * 解密仅在 {@code LlmAgentClient} 内即用即弃。
 */
public record LineageAgentConfig(
        Long id,
        long tenantId,
        String protocol,       // ANTHROPIC | OPENAI
        String baseUrl,
        String model,
        String apiKeyEnc,      // 加密密文；null=免鉴权网关
        boolean enabled,
        int timeoutMs,
        int rateLimitPerMin,
        int maxColumns,
        Long createdBy,
        Long updatedBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        int deleted,
        int version
) {}
