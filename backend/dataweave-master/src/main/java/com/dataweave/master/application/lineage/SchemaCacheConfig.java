package com.dataweave.master.application.lineage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 053 schema 缓存配置（FR-018）。
 * 提供可配置 TTL 供 {@link DatasourceBoundCatalog} 构造用。
 * TTL 默认 6h（21600000ms），可经 {@code lineage.schema-cache.ttl} 覆盖。
 */
@Component
public class SchemaCacheConfig {

    /** 进程内 schema 缓存 TTL（毫秒），默认 6 小时。 */
    private final long ttlMs;

    public SchemaCacheConfig(@Value("${lineage.schema-cache.ttl:21600000}") long ttlMs) {
        this.ttlMs = ttlMs > 0 ? ttlMs : 21600000;
    }

    public long ttlMs() {
        return ttlMs;
    }
}
