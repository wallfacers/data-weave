package com.dataweave.master.domain.lineage;

import java.time.LocalDateTime;

/**
 * 053 云 AI Agent 抽取配置（lineage_agent_config 表）。按租户/项目隔离，每项目一条，默认 enabled=false（FR-019）。
 * api_key_enc 经 {@code DatasourceEncryptor} 加密，明文绝不入库/日志（FR-020）；
 * 解密仅在 {@code LlmAgentClient} 内即用即弃。
 */
public record LineageAgentConfig(
        Long id,
        long tenantId,
        long projectId,
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
