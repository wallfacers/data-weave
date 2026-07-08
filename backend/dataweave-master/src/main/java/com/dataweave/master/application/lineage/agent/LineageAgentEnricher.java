package com.dataweave.master.application.lineage.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.dataweave.master.application.DatasourceSchemaResolver;
import com.dataweave.master.application.lineage.ColumnLineageCatalog;
import com.dataweave.master.application.lineage.ColumnMeta;
import com.dataweave.master.application.lineage.DatasourceBoundCatalog;
import com.dataweave.master.application.lineage.LineageEdgeAssembler;
import com.dataweave.master.application.lineage.TableSchema;
import com.dataweave.master.application.lineage.script.ScriptExtraction;
import com.dataweave.master.application.lineage.script.ScriptLineageService;
import com.dataweave.master.application.lineage.script.ScriptSource;
import com.dataweave.master.application.lineage.grounding.CatalogGroundingService;
import com.dataweave.master.application.lineage.grounding.GroundingDisposition;
import com.dataweave.master.domain.Datasource;
import com.dataweave.master.domain.DatasourceRepository;
import com.dataweave.master.domain.EventBus;
import com.dataweave.master.domain.TaskDef;
import com.dataweave.master.domain.TaskDefRepository;
import com.dataweave.master.domain.lineage.ColumnEdge;
import com.dataweave.master.domain.lineage.Confidence;
import com.dataweave.master.domain.lineage.DatasourceCoord;
import com.dataweave.master.domain.lineage.Direction;
import com.dataweave.master.domain.lineage.IoEdge;
import com.dataweave.master.domain.lineage.LineageAgentConfig;
import com.dataweave.master.domain.lineage.LineageStore;
import com.dataweave.master.domain.lineage.Source;
import com.dataweave.master.domain.lineage.TableRef;
import com.dataweave.master.domain.lineage.Transform;
import com.dataweave.master.infrastructure.lineage.AgentConfigRepository;
import com.dataweave.master.infrastructure.lineage.GroundingDispositionRepository;
import com.dataweave.master.infrastructure.lineage.Neo4jColumnBackfillWriter;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

/**
 * 053 异步富化编排器（契约 agent-lineage-extractor C3，FR-004b）。订阅 {@link LineageAgentEnrichmentRequested#CHANNEL}，
 * 在有界线程池内消费：重算确定性全集（assembly + script）+ 合并 AI 边（按 CHANNEL_PRIORITY 消解）→
 * 一次 {@code recordTaskIo} keyed replace（确定性边已重新包含，不擦除）→ 写 {@code lineage_agent_call} 审计。
 *
 * <p>不变量（C3）：① 任一步失败绝不回滚已入图谱的确定性血缘（recordTaskIo 在一个事务内 replace，失败回滚保持旧态）；
 * ② 未启用项目零外呼（enrich 提前返回）；③ 外呼总耗时 ≤ cfg.timeoutMs（由 LlmAgentClient 控制）。
 */
@Component
public class LineageAgentEnricher {

    private static final Logger log = LoggerFactory.getLogger(LineageAgentEnricher.class);

    /** 默认线程池容量（可配）。 */
    private static final int DEFAULT_POOL_SIZE = 2;

    /** 异步有界线程池（守护线程，避免阻塞 push 线程）。容量由 poolSize 配置控制。 */
    private final ExecutorService pool;

    private final EventBus eventBus;
    private final ObjectMapper objectMapper;
    private final TaskDefRepository taskDefRepository;
    private final LineageEdgeAssembler assembler;
    private final ScriptLineageService scriptLineageService;
    private final AgentLineageConfigService configService;
    private final AgentConfigRepository agentConfigRepository;
    private final LineageStore lineageStore;
    private final AgentLineageExtractor extractor;  // 非 bean，enricher 直接持有（不入同步 extractors 列表）

    // ── US3 schema 接地 + US4 治理 ─────────────────────────────────────
    private final DatasourceSchemaResolver schemaResolver;
    private final Neo4jColumnBackfillWriter backfillWriter;
    private final ColumnLineageCatalog neo4jCatalog;
    private final DatasourceRepository datasourceRepository;

    // ── 055 目录接地 ────────────────────────────────────────────────────
    private final CatalogGroundingService groundingService;
    private final GroundingDispositionRepository dispositionRepository;
    /** 全局 kill-switch（FR-014）：默认 true；false 时回退到接地前行为。 */
    private final boolean groundingEnabled;

    /** 每配置令牌桶（FR-023/T033）：config_id → 上次填充纳秒 + 可用令牌数。 */
    private final ConcurrentHashMap<Long, TokenBucket> rateLimiters = new ConcurrentHashMap<>();

    public LineageAgentEnricher(EventBus eventBus, ObjectMapper objectMapper,
                                TaskDefRepository taskDefRepository,
                                LineageEdgeAssembler assembler,
                                ScriptLineageService scriptLineageService,
                                AgentLineageConfigService configService,
                                AgentConfigRepository agentConfigRepository,
                                LineageStore lineageStore,
                                LlmAgentClient llmClient,
                                DatasourceSchemaResolver schemaResolver,
                                Neo4jColumnBackfillWriter backfillWriter,
                                ColumnLineageCatalog neo4jCatalog,
                                DatasourceRepository datasourceRepository,
                                CatalogGroundingService groundingService,
                                GroundingDispositionRepository dispositionRepository,
                                @Value("${lineage.grounding.enabled:true}") boolean groundingEnabled,
                                @Value("${lineage.agent-enrich.pool-size:2}") int poolSize) {
        this.eventBus = eventBus;
        this.objectMapper = objectMapper;
        this.taskDefRepository = taskDefRepository;
        this.assembler = assembler;
        this.scriptLineageService = scriptLineageService;
        this.configService = configService;
        this.agentConfigRepository = agentConfigRepository;
        this.lineageStore = lineageStore;
        this.extractor = new AgentLineageExtractor(llmClient, configService);
        this.schemaResolver = schemaResolver;
        this.backfillWriter = backfillWriter;
        this.neo4jCatalog = neo4jCatalog;
        this.datasourceRepository = datasourceRepository;
        this.groundingService = groundingService;
        this.dispositionRepository = dispositionRepository;
        this.groundingEnabled = groundingEnabled;
        int ps = Math.max(1, Math.min(poolSize, 64));
        this.pool = Executors.newFixedThreadPool(ps, r -> {
            Thread t = new Thread(r, "lineage-agent-enrich");
            t.setDaemon(true);
            return t;
        });
    }

    @PostConstruct
    void subscribe() {
        // publish 在 push 线程同步调本 handler；handler 仅 submit 到池，立即返回不阻塞 push（FR-004b）
        eventBus.subscribe(LineageAgentEnrichmentRequested.CHANNEL, json -> pool.submit(() -> handle(json)));
    }

    private void handle(String json) {
        LineageAgentEnrichmentRequested ev;
        try {
            ev = objectMapper.readValue(json, LineageAgentEnrichmentRequested.class);
        } catch (Exception e) {
            log.warn("[LineageAgent] enrichment event decode failed (dropped): {}", e.toString());
            return;
        }
        try {
            enrich(ev);
        } catch (Exception e) {
            log.warn("[LineageAgent] enrich unexpected failure (bypassed, FR-004b): {}", e.toString());
        }
    }

    private void enrich(LineageAgentEnrichmentRequested ev) {
        // AI 通道激活判定（原 C3 step 1：未启用 / 不该 AI → 不外呼）。057：配置为租户全局单例。
        Optional<LineageAgentConfig> cfgOpt = configService.getActive(ev.tenantId());
        boolean aiActive = cfgOpt.isPresent() && cfgOpt.get().enabled()
                && extractor.shouldEnrich(ev.taskType(), ev.calciteParsed());

        TaskDef task = taskDefRepository.findById(ev.taskDefId()).orElse(null);
        if (task == null) return;

        // 055：grounding 独立于 AI——绑定数据源且 kill-switch 开即激活（FR-014）
        boolean dsBound = task.getDatasourceId() != null || task.getTargetDatasourceId() != null;
        boolean groundingActive = groundingEnabled && dsBound;

        // 两条能力都不激活 → 保持接地前的完全早退（AI-off 且未接地：不重算、不 replace）
        if (!aiActive && !groundingActive) return;

        LineageAgentConfig cfg = aiActive ? cfgOpt.get() : null;

        // T033/US4：令牌桶限频仅约束 AI 外呼；超限只跳过 AI，grounding 仍在确定性集上运行（FR-023）
        if (aiActive && !tryAcquire(cfg)) {
            log.debug("[LineageAgent] rate-limited for config {} (project={})", cfg.id(), ev.projectId());
            agentConfigRepository.insertCall(ev.tenantId(), ev.projectId(), cfg.id(), cfg.protocol(),
                    ev.taskDefId(), 0, "DEGRADED", 0, "rate limited");
            aiActive = false;
            cfg = null;
            if (!groundingActive) return;
        }

        long t0 = System.nanoTime();
        String status = "SUCCESS";
        int edgesEmitted = 0;
        String note = null;
        try {
            String type = task.getType();
            String content = task.getContent() == null ? "" : task.getContent();
            Long dsId = task.getDatasourceId();
            Long targetDsId = task.getTargetDatasourceId();
            DatasourceCoord readCoord = assembler.resolveCoord(ev.tenantId(), ev.projectId(), dsId);
            DatasourceCoord writeCoord = assembler.resolveCoord(ev.tenantId(), ev.projectId(),
                    targetDsId != null ? targetDsId : dsId);

            // 重算确定性全集（assembly = Calcite/Agent 声明 A×B；script = 脚本通道）
            List<IoEdge> ioEdges = new ArrayList<>();
            List<ColumnEdge> columnEdges = new ArrayList<>();
            List<String> agentReads = ev.agentReads() != null ? ev.agentReads() : List.of();
            List<String> agentWrites = ev.agentWrites() != null ? ev.agentWrites() : List.of();
            LineageEdgeAssembler.Assembly assembly = assembler.assemble(
                    ev.tenantId(), ev.projectId(), type, content, agentReads, agentWrites, dsId, targetDsId);
            ioEdges.addAll(assembly.ioEdges());
            if (scriptLineageService.handles(type)) {
                ScriptSource src = new ScriptSource(ev.tenantId(), ev.projectId(), ev.taskDefId(),
                        type, content, dsId, targetDsId);
                ScriptLineageService.Result r = scriptLineageService.extract(src);
                ioEdges.addAll(r.ioEdges());
                columnEdges.addAll(r.columnEdges());
            }

            // AI 边（仅 AI 激活时；防幻觉 + schema 接地已在 AgentLineageExtractor.extract 内应用）
            ScriptExtraction ai = null;
            if (aiActive) {
                Map<String, Set<String>> schemaContext = resolveSchemaContext(
                        ev.tenantId(), ev.projectId(), dsId, agentReads, agentWrites);
                ai = extractor.extract(new ScriptSource(ev.tenantId(), ev.projectId(),
                        ev.taskDefId(), type, content, dsId, targetDsId), schemaContext);
                String modelVersion = ai.modelVersion();
                for (String t : ai.reads()) {
                    ioEdges.add(new IoEdge(assembler.tableRef(readCoord, t), Direction.READS,
                            Source.SCRIPT_AGENT, Confidence.UNVERIFIED, modelVersion));
                }
                for (String t : ai.writes()) {
                    ioEdges.add(new IoEdge(assembler.tableRef(writeCoord, t), Direction.WRITES,
                            Source.SCRIPT_AGENT, Confidence.UNVERIFIED, modelVersion));
                }
                for (com.dataweave.master.application.lineage.ColumnEdge ace : ai.columnEdges()) {
                    columnEdges.add(new ColumnEdge(
                            assembler.tableRef(readCoord, ace.srcTable().qualifiedName()), ace.srcCol(),
                            assembler.tableRef(writeCoord, ace.dstTable().qualifiedName()), ace.dstCol(),
                            Transform.DIRECT, Confidence.UNVERIFIED, Source.SCRIPT_AGENT));
                }
            }

            // 按 (direction, tableKey) + CHANNEL_PRIORITY（+CONFIRMED 次级偏好，FR-012）消解
            List<IoEdge> mergedIo = dedupeIo(ioEdges);
            List<ColumnEdge> mergedCol = dedupeCol(columnEdges);

            // 055 grounding：replace 前接地（异步路径，push 零开销）——PRESENT 升级 / ABSENT+系统 剔除（仅推断类）。
            // 整段包 try：grounding 故障 → 保持接地前边集，recordTaskIo 仍写（SC-004，绝不断链）。
            if (groundingActive) {
                try {
                    Long writeDsId = targetDsId != null ? targetDsId : dsId;
                    DatasourceBoundCatalog readCatalog = new DatasourceBoundCatalog(
                            dsId, neo4jCatalog, schemaResolver, backfillWriter, datasourceRepository);
                    DatasourceBoundCatalog writeCatalog = new DatasourceBoundCatalog(
                            writeDsId, neo4jCatalog, schemaResolver, backfillWriter, datasourceRepository);
                    CatalogGroundingService.GroundingResult gr = groundingService.ground(
                            ev.tenantId(), ev.projectId(), ev.taskDefId(),
                            mergedIo, mergedCol,
                            readCatalog, dsId, engineOf(dsId),
                            writeCatalog, writeDsId, engineOf(writeDsId));
                    mergedIo = gr.ioEdges();
                    mergedCol = gr.columnEdges();
                    for (GroundingDisposition d : gr.dispositions()) {
                        try {
                            dispositionRepository.insert(ev.tenantId(), ev.projectId(), ev.taskDefId(), d);
                        } catch (Exception e) {
                            log.debug("[grounding] audit insert failed for {}: {}", d.candidate(), e.toString());
                        }
                    }
                } catch (Exception e) {
                    log.warn("[grounding] stage failed, retaining ungrounded edges (SC-004): {}", e.toString());
                }
            }

            // 一次全量 keyed replace（确定性边已重新包含 → 不擦除）
            lineageStore.recordTaskIo(ev.tenantId(), ev.projectId(), ev.taskDefId(),
                    task.getCurrentVersionNo(), task.getName(), mergedIo, mergedCol, null);

            if (aiActive) {
                long agentIo = mergedIo.stream().filter(e -> e.source() == Source.SCRIPT_AGENT).count();
                boolean hasTimeout = ai.hints().stream()
                        .anyMatch(h -> h.kind() == ScriptExtraction.HintKind.TIMEOUT);
                edgesEmitted = (int) agentIo;
                status = agentIo > 0 ? "SUCCESS" : (hasTimeout ? "DEGRADED" : "REJECTED");
            }
        } catch (Exception e) {
            log.warn("[LineageAgent] enrich degraded: {}", e.toString());
            status = "DEGRADED";
            note = abbreviate(e.toString());
        }
        long latency = (System.nanoTime() - t0) / 1_000_000;
        // C3 step 7：AI 外呼审计（仅 AI 激活；grounding 处置已单独落 lineage_grounding_disposition）
        if (cfg != null) {
            agentConfigRepository.insertCall(ev.tenantId(), ev.projectId(), cfg.id(), cfg.protocol(),
                    ev.taskDefId(), (int) Math.min(latency, Integer.MAX_VALUE), status, edgesEmitted, note);
        }
    }

    /** 取数据源引擎 typeCode（用于系统命名空间判定）；null/缺失 → null。 */
    private String engineOf(Long datasourceId) {
        if (datasourceId == null) return null;
        try {
            return datasourceRepository.findById(datasourceId)
                    .map(Datasource::getTypeCode).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** 表级边消解：同 (direction, dsKey, qualifiedName.lower) 取 CHANNEL_PRIORITY 最高者。 */
    private static List<IoEdge> dedupeIo(List<IoEdge> ioEdges) {
        Map<String, IoEdge> winners = new LinkedHashMap<>();
        for (IoEdge e : ioEdges) {
            String dsKey = (e.table().datasource() != null) ? e.table().datasource().dsKey() : "";
            String qn = (e.table().qualifiedName() != null) ? e.table().qualifiedName().toLowerCase(Locale.ROOT) : "";
            String key = e.direction().name() + "|" + dsKey + "|" + qn;
            IoEdge cur = winners.get(key);
            if (cur == null || better(priority(e.source()), e.confidence(),
                    priority(cur.source()), cur.confidence())) winners.put(key, e);
        }
        return new ArrayList<>(winners.values());
    }

    /** 列级边消解：同 (srcQn, srcCol, dstQn, dstCol) 取优先级最高者。 */
    private static List<ColumnEdge> dedupeCol(List<ColumnEdge> columnEdges) {
        Map<String, ColumnEdge> winners = new LinkedHashMap<>();
        for (ColumnEdge e : columnEdges) {
            String key = (qn(e.srcTable()) + "|" + e.srcCol() + "|" + qn(e.dstTable()) + "|" + e.dstCol())
                    .toLowerCase(Locale.ROOT);
            ColumnEdge cur = winners.get(key);
            if (cur == null || better(priority(e.source()), e.confidence(),
                    priority(cur.source()), cur.confidence())) winners.put(key, e);
        }
        return new ArrayList<>(winners.values());
    }

    /**
     * 候选边是否胜过当前赢家：先比来源优先级（数字小=高）；同级时目录已核验（CONFIRMED）胜未核验（FR-012）。
     */
    private static boolean better(int candPrio, Confidence candConf, int curPrio, Confidence curConf) {
        if (candPrio != curPrio) return candPrio < curPrio;
        return confRank(candConf) > confRank(curConf);
    }

    /** CONFIRMED（catalog-verified）为最高可信信号。 */
    private static int confRank(Confidence c) {
        return c == Confidence.CONFIRMED ? 1 : 0;
    }

    private static String qn(TableRef t) {
        return t != null && t.qualifiedName() != null ? t.qualifiedName() : "";
    }

    /**
     * FR-004a 优先级：SQL_PARSED/SCRIPT_SQL（Calcite/内嵌）&gt; 规则 &gt; AI &gt; 小模型。
     * null（既有 SQL 列级路径无 source）视为确定性最高，绝不被 AI 覆盖。
     */
    private static int priority(Source s) {
        if (s == null) return -1;
        return switch (s) {
            case SQL_PARSED, SCRIPT_SQL -> 0;
            case SCRIPT_INFERRED -> 1;
            case SCRIPT_AGENT -> 2;
            case SCRIPT_MODEL -> 3;
            case AGENT -> 4;
            case FORM -> 5;
        };
    }

    private static String abbreviate(String s) {
        return s == null ? null : (s.length() > 200 ? s.substring(0, 200) : s);
    }

    // ── US3 schema 接地（T028-T029）────────────────────────────────────

    /**
     * 解析候选表的真实列清单（FR-016）。
     * 为 agentReads/agentWrites 中每个表名查询 DatasourceBoundCatalog，
     * 返回 tableName → {列名集合}。
     */
    private Map<String, Set<String>> resolveSchemaContext(long tenantId, long projectId,
                                                           Long datasourceId,
                                                           List<String> reads, List<String> writes) {
        if (datasourceId == null) return Collections.emptyMap();
        Set<String> candidateTables = new LinkedHashSet<>();
        if (reads != null) candidateTables.addAll(reads);
        if (writes != null) candidateTables.addAll(writes);
        if (candidateTables.isEmpty()) return Collections.emptyMap();

        DatasourceBoundCatalog catalog = new DatasourceBoundCatalog(
                datasourceId, neo4jCatalog, schemaResolver, backfillWriter, datasourceRepository);
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (String table : candidateTables) {
            if (table == null || table.isBlank()) continue;
            try {
                Optional<TableSchema> schema = catalog.lookupTable(tenantId, projectId, table.trim());
                if (schema.isPresent()) {
                    Set<String> cols = schema.get().columns().stream()
                            .map(ColumnMeta::name)
                            .filter(c -> c != null && !c.isBlank())
                            .collect(Collectors.toCollection(LinkedHashSet::new));
                    if (!cols.isEmpty()) {
                        result.put(table.trim(), cols);
                    }
                }
            } catch (Exception e) {
                log.debug("[LineageAgent] schema resolve failed for table {}: {}", table, e.getMessage());
            }
        }
        return result;
    }

    // ── US4 令牌桶限频（T033）─────────────────────────────────────────

    /**
     * 尝试获取一次外呼许可（FR-023）。
     * 每配置独立令牌桶：每分钟最多 {@code rateLimitPerMin} 次，桶容量 = rateLimitPerMin。
     * @return true=允许外呼，false=超限需跳过
     */
    private boolean tryAcquire(LineageAgentConfig cfg) {
        long now = System.nanoTime();
        TokenBucket bucket = rateLimiters.computeIfAbsent(cfg.id(),
                id -> new TokenBucket(cfg.rateLimitPerMin(), now));
        return bucket.tryAcquire(now);
    }

    /** 简单令牌桶：QPM 限频，纳秒精度。线程安全。 */
    private static final class TokenBucket {
        private final double ratePerNano;  // 令牌/纳秒
        private final long capacity;
        private double tokens;
        private long lastRefillNano;

        TokenBucket(long perMinute, long nowNano) {
            this.capacity = perMinute;
            this.ratePerNano = (double) perMinute / TimeUnit.MINUTES.toNanos(1);
            this.tokens = perMinute;  // 初始满桶
            this.lastRefillNano = nowNano;
        }

        synchronized boolean tryAcquire(long nowNano) {
            // 按时间差补充令牌
            double added = (nowNano - lastRefillNano) * ratePerNano;
            tokens = Math.min(capacity, tokens + added);
            lastRefillNano = nowNano;
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }
}
