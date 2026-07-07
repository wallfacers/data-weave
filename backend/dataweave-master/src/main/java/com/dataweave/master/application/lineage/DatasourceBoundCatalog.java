package com.dataweave.master.application.lineage;

import com.dataweave.master.application.DatasourceSchemaResolver;
import com.dataweave.master.application.lineage.grounding.TableExistence;
import com.dataweave.master.domain.Datasource;
import com.dataweave.master.domain.DatasourceRepository;
import com.dataweave.master.domain.lineage.DatasourceCoord;
import com.dataweave.master.infrastructure.lineage.Neo4jColumnBackfillWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 绑定数据源的组合列目录（FR-008/FR-013/FR-017）。
 *
 * <p>实现 {@link ColumnLineageCatalog}，按优先级链解析表列元数据：
 * <ol>
 *   <li><b>进程内 TTL 缓存</b>：命中直接返回（避免重复连库，SC-006）</li>
 *   <li><b>neo4j 持久列目录</b>（{@link com.dataweave.master.infrastructure.lineage.Neo4jColumnLineageCatalog}）：
 *       命中 → 回填进程缓存 → 返回</li>
 *   <li><b>数据源实时抓取</b>（{@link DatasourceSchemaResolver}）：
 *       命中 → 回填 neo4j（{@link Neo4jColumnBackfillWriter}）+ 进程缓存 → 返回</li>
 *   <li><b>全 miss</b> → {@link Optional#empty()}（触发既有列级降级，FR-013）</li>
 * </ol>
 *
 * <p><b>datasourceId 闭包持有</b>：不改 {@link ColumnLineageCatalog#lookupTable} 接口签名，
 * 在构造时注入数据源 ID；{@code datasourceId=null} 时跳过步骤 3，退化为纯 neo4j catalog
 * （FR-013 场景 6：任务未绑定数据源）。
 *
 * <p><b>新鲜度（FR-018）</b>：进程缓存带 TTL 兜底（默认 6h）；
 * 调用方在 push 前先 {@link #evict(String)} 候选表条目实现「重 push 失效」。
 *
 * <p><b>安全边界</b>：永不抛异常——任何步骤失败均降级到下一层，最终返回 empty。
 */
public class DatasourceBoundCatalog implements ColumnLineageCatalog {

    private static final Logger log = LoggerFactory.getLogger(DatasourceBoundCatalog.class);

    /** 进程内 TTL 缓存（跨 DatasourceBoundCatalog 实例共享）。 */
    private static final ConcurrentMap<String, CacheEntry> CACHE = new ConcurrentHashMap<>();

    /** 默认 TTL（毫秒），6 小时。 */
    static final long DEFAULT_TTL_MS = 6 * 60 * 60 * 1000;

    private final Long datasourceId;
    private final ColumnLineageCatalog neo4jCatalog;
    private final DatasourceSchemaResolver schemaResolver;
    private final Neo4jColumnBackfillWriter backfillWriter;
    private final DatasourceRepository datasourceRepository;
    private final long ttlMs;

    /**
     * @param datasourceId       任务绑定的数据源 ID（可 null——退化纯 neo4j）
     * @param neo4jCatalog       底层 neo4j 列目录（用于步骤 2 读取）
     * @param schemaResolver     数据源实时 schema 抓取器（步骤 3）
     * @param backfillWriter     neo4j 列回填器（步骤 3 命中后写回）
     * @param datasourceRepository 数据源仓储（用于构建 DatasourceCoord）
     */
    public DatasourceBoundCatalog(Long datasourceId,
                                  ColumnLineageCatalog neo4jCatalog,
                                  DatasourceSchemaResolver schemaResolver,
                                  Neo4jColumnBackfillWriter backfillWriter,
                                  DatasourceRepository datasourceRepository) {
        this(datasourceId, neo4jCatalog, schemaResolver, backfillWriter, datasourceRepository, DEFAULT_TTL_MS);
    }

    /** 全参构造（供测试/配置注入自定义 TTL）。 */
    public DatasourceBoundCatalog(Long datasourceId,
                           ColumnLineageCatalog neo4jCatalog,
                           DatasourceSchemaResolver schemaResolver,
                           Neo4jColumnBackfillWriter backfillWriter,
                           DatasourceRepository datasourceRepository,
                           long ttlMs) {
        this.datasourceId = datasourceId;
        this.neo4jCatalog = neo4jCatalog;
        this.schemaResolver = schemaResolver;
        this.backfillWriter = backfillWriter;
        this.datasourceRepository = datasourceRepository;
        this.ttlMs = ttlMs;
    }

    // ── ColumnLineageCatalog 实现 ───────────────────────────────────────

    @Override
    public Optional<TableSchema> lookupTable(long tenantId, long projectId, String qualifiedName) {
        if (qualifiedName == null || qualifiedName.isBlank()) {
            return Optional.empty();
        }

        // 步骤 1：进程内 TTL 缓存
        String cacheKey = cacheKey(qualifiedName);
        CacheEntry cached = CACHE.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            log.debug("DatasourceBoundCatalog: cache hit for {}", qualifiedName);
            return Optional.of(cached.schema());
        }

        // 步骤 2：neo4j 持久列目录
        try {
            Optional<TableSchema> neo4jResult = neo4jCatalog.lookupTable(tenantId, projectId, qualifiedName);
            if (neo4jResult.isPresent()) {
                TableSchema schema = neo4jResult.get();
                CACHE.put(cacheKey, new CacheEntry(schema, expireAt()));
                log.debug("DatasourceBoundCatalog: neo4j hit for {}", qualifiedName);
                return Optional.of(schema);
            }
        } catch (Exception e) {
            log.debug("DatasourceBoundCatalog: neo4j lookup failed for {}: {}", qualifiedName, e.getMessage());
        }

        // 步骤 3：数据源实时抓取（仅当绑定了数据源）
        if (datasourceId != null) {
            try {
                Optional<TableSchema> liveResult = schemaResolver.fetchColumns(datasourceId, qualifiedName);
                if (liveResult.isPresent()) {
                    TableSchema schema = liveResult.get();
                    // 回填 neo4j（FR-017）
                    try {
                        Datasource ds = datasourceRepository.findById(datasourceId)
                                .filter(d -> d.getDeleted() == null || d.getDeleted() == 0)
                                .orElse(null);
                        if (ds != null) {
                            DatasourceCoord coord = new DatasourceCoord(
                                    tenantId, projectId,
                                    ds.getHost(), ds.getPort(), ds.getDatabaseName(), ds.getName());
                            backfillWriter.backfillColumns(coord, qualifiedName, schema.columns(),
                                    tenantId, projectId);
                        }
                    } catch (Exception e) {
                        // 回填失败不阻断——已拿到 schema，可继续解析
                        log.debug("DatasourceBoundCatalog: neo4j backfill failed for {}: {}",
                                qualifiedName, e.getMessage());
                    }
                    // 回填进程缓存
                    CACHE.put(cacheKey, new CacheEntry(schema, expireAt()));
                    log.debug("DatasourceBoundCatalog: live fetch hit for {}", qualifiedName);
                    return Optional.of(schema);
                }
            } catch (Exception e) {
                log.debug("DatasourceBoundCatalog: live fetch failed for {}: {}", qualifiedName, e.getMessage());
            }
        }

        // 步骤 4：全 miss → 降级
        return Optional.empty();
    }

    // ── 三态存在性探针（055 目录接地，契约 table-existence-probe.md C2）─────

    /**
     * 组合链三态存在性：cache/neo4j 命中即 {@link TableExistence#PRESENT}；
     * miss 且绑定数据源 → live probe；未绑定 → {@link TableExistence#UNKNOWN}。永不抛。
     *
     * <p><b>不缓存 ABSENT/UNKNOWN</b>：保证新建/删除表在下次 push 翻转结论（FR-013）。
     */
    public TableExistence probeExistence(long tenantId, long projectId, String qualifiedName) {
        if (qualifiedName == null || qualifiedName.isBlank()) {
            return TableExistence.UNKNOWN;
        }
        // 步骤 1：进程缓存命中即证存在
        CacheEntry cached = CACHE.get(cacheKey(qualifiedName));
        if (cached != null && !cached.isExpired()) {
            return TableExistence.PRESENT;
        }
        // 步骤 2：neo4j 持久列目录命中即证存在（回填缓存）
        try {
            Optional<TableSchema> neo4jResult = neo4jCatalog.lookupTable(tenantId, projectId, qualifiedName);
            if (neo4jResult.isPresent()) {
                CACHE.put(cacheKey(qualifiedName), new CacheEntry(neo4jResult.get(), expireAt()));
                return TableExistence.PRESENT;
            }
        } catch (Exception e) {
            log.debug("DatasourceBoundCatalog: probe neo4j lookup failed for {}: {}", qualifiedName, e.getMessage());
        }
        // 步骤 3：未绑定数据源 → 无法判定
        if (datasourceId == null) {
            return TableExistence.UNKNOWN;
        }
        // 步骤 4：数据源实时三态探测
        try {
            return schemaResolver.probeTable(datasourceId, qualifiedName);
        } catch (Exception e) {
            log.debug("DatasourceBoundCatalog: probeTable failed for {}: {}", qualifiedName, e.getMessage());
            return TableExistence.UNKNOWN;
        }
    }

    // ── 新鲜度管理 ──────────────────────────────────────────────────────

    /**
     * 失效指定表的进程缓存（FR-018：重 push 失效）。
     * 调用方在 push 前对候选表（reads + writes）逐表调用。
     */
    public void evict(String qualifiedName) {
        if (qualifiedName != null) {
            CACHE.remove(cacheKey(qualifiedName));
            log.debug("DatasourceBoundCatalog: evicted cache for {}", qualifiedName);
        }
    }

    /** 失效该数据源下所有缓存条目（批量操作）。 */
    public void evictAll() {
        String prefix = cacheKeyPrefix();
        CACHE.keySet().removeIf(k -> k.startsWith(prefix));
        log.debug("DatasourceBoundCatalog: evicted all cache entries for datasource {}", datasourceId);
    }

    // ── 内部 helper ─────────────────────────────────────────────────────

    private String cacheKey(String qualifiedName) {
        return cacheKeyPrefix() + "|" + qualifiedName;
    }

    private String cacheKeyPrefix() {
        return "ds:" + (datasourceId != null ? datasourceId : "none");
    }

    private long expireAt() {
        return System.currentTimeMillis() + ttlMs;
    }

    private record CacheEntry(TableSchema schema, long expireAt) {
        boolean isExpired() {
            return System.currentTimeMillis() > expireAt;
        }
    }

    /** 暴露缓存大小（供测试/监控）。 */
    static int cacheSize() {
        return CACHE.size();
    }

    /** 清空全量缓存（供测试重置）。 */
    public static void clearCache() {
        CACHE.clear();
    }
}
