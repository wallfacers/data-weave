package com.dataweave.master.application;

import com.dataweave.master.i18n.BizException;
import com.dataweave.master.lineage.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Cypher 变长路径血缘查询服务 —— 取代 {@link LineageGraphService} 中 JDBC 内存 BFS
 * 的读侧方法，运行在 018 提供的只读 neo4j 会话上。
 *
 * <h3>有界查询（FR-004 / SC-004）</h3>
 * 深度上界 {@link #MAX_DEPTH}=20、节点上界 {@link #MAX_NODES}=2000、分页默认 100；
 * 触上界 → truncated=true + log.warn，不静默丢。
 *
 * <h3>租户隔离（FR-003）</h3>
 * 所有查询入参带 (tenantId, projectId)，Cypher WHERE 过滤。
 *
 * <h3>052 扩展</h3>
 * 节点 attrs 富化（layer/producers/syncedRowsToday/lastSyncDate）、
 * 服务端过滤（layers/types/confidences/sources）、neighborhood 双向带边、
 * 影响面边+可达总数、按名搜索、两点路径。
 */
@Service
public class LineageQueryService {

    private static final Logger log = LoggerFactory.getLogger(LineageQueryService.class);

    public static final int MAX_DEPTH = 20;
    public static final int MAX_NODES = 2000;
    public static final int DEFAULT_LIMIT = 100;
    /** 影响面可达总数计数帽（FR-013）。 */
    public static final int COUNT_CAP = 10000;
    /** 路径数帽（FR-014）。 */
    public static final int PATH_CAP = 200;
    /** 搜索结果硬顶。 */
    public static final int MAX_SEARCH_RESULTS = 2000;

    private final LineageGraphReader reader;
    private final org.springframework.beans.factory.ObjectProvider<
            com.dataweave.master.application.lineage.LineageCorrectionService> correctionService;

    public LineageQueryService(LineageGraphReader reader,
                               org.springframework.beans.factory.ObjectProvider<
                                       com.dataweave.master.application.lineage.LineageCorrectionService> correctionService) {
        this.reader = reader;
        this.correctionService = correctionService;
    }

    /**
     * 041 读侧裁决注解（FR-004/FR-007）：REMOVED 语义键边兜底过滤（正常已在写侧抑制），
     * CONFIRMED 键回填 humanState。PG 不可达 → 原样返回（降级）。
     */
    private List<FlowEdgeView> annotateCorrections(long tenantId, long projectId, List<FlowEdgeView> edges) {
        if (edges.isEmpty()) {
            return edges;
        }
        try {
            var svc = correctionService.getIfAvailable();
            if (svc == null) {
                return edges;
            }
            Set<Long> taskIds = new LinkedHashSet<>();
            for (FlowEdgeView e : edges) {
                if (e.taskDefId() != null) {
                    taskIds.add(e.taskDefId());
                }
            }
            Map<String, String> decisions = svc.decisionsForTasks(tenantId, projectId, taskIds);
            if (decisions.isEmpty()) {
                return edges;
            }
            List<FlowEdgeView> out = new ArrayList<>(edges.size());
            for (FlowEdgeView e : edges) {
                if (e.taskDefId() == null) {
                    out.add(e);
                    continue;
                }
                // 节点 id 与语义键同构：表级 = tableKey，列级 = tableKey|col（columnKey）
                String r = decisions.get(e.taskDefId() + "|READ|" + e.from());
                String w = decisions.get(e.taskDefId() + "|WRITE|" + e.to());
                if ("REMOVED".equals(r) || "REMOVED".equals(w)) {
                    continue;   // 兜底：已剔除语义键不出图（SC-005）
                }
                if ("CONFIRMED".equals(r) || "CONFIRMED".equals(w)) {
                    out.add(new FlowEdgeView(e.from(), e.to(), e.granularity(), e.taskDefId(),
                            e.confidence(), e.transform(), e.source(), "CONFIRMED", e.modelVersion()));
                } else {
                    out.add(e);
                }
            }
            return out;
        } catch (Exception ex) {
            log.debug("correction annotate degraded: {}", ex.toString());
            return edges;
        }
    }

    // ─── 工具方法 ──────────────────────────────────────────────

    public static int clampDepth(int requested) {
        if (requested <= 0 || requested > MAX_DEPTH) return MAX_DEPTH;
        return requested;
    }

    public static int clampLimit(int requested) {
        if (requested <= 0) return DEFAULT_LIMIT;
        return Math.min(requested, MAX_NODES);
    }

    static int clampSearchLimit(int requested) {
        if (requested <= 0) return DEFAULT_LIMIT;
        return Math.min(requested, MAX_SEARCH_RESULTS);
    }

    static void logTruncation(String anchorId, int depth, int nodeCount) {
        log.warn("Lineage query truncated: anchor={}, depth={}, truncatedNodeCount={}", anchorId, depth, nodeCount);
    }

    static BizException storeUnavailable(Throwable cause) {
        return (BizException) new BizException("lineage.store_unavailable")
                .withHttpStatus(503)
                .initCause(cause);
    }

    // ─── 安全执行 ──────────────────────────────────────────────

    private List<Map<String, Object>> execute(String cypher, Map<String, Object> params) {
        try {
            return reader.execute(cypher, params);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            String exName = e.getClass().getName();
            // 仅「真连通性异常」降级为 store_unavailable；ClientException/DatabaseException
            // （Cypher 语法/语义错误）是查询缺陷，必须原样抛 500——不能被宽泛的 "neo4j"
            // 匹配掩盖成「存储不可用」（否则真实 Cypher bug 会被伪装成降级而漏过）。
            boolean connectivity = exName.contains("ServiceUnavailable")
                    || exName.contains("SessionExpired")
                    || exName.contains("ConnectionRead")
                    || exName.contains("Discovery")
                    || exName.contains("ConnectException")
                    || exName.contains("UnknownHost");
            if (connectivity) {
                throw storeUnavailable(e);
            }
            throw new RuntimeException("Lineage query failed: " + e.getMessage(), e);
        }
    }

    // ─── 行→视图映射辅助 ──────────────────────────────────────

    private static GraphNodeView mapNode(Map<String, Object> row) {
        GraphNodeView.NodeType type = GraphNodeView.NodeType.valueOf((String) row.get("type"));
        String id = (String) row.get("id");
        String name = (String) row.get("name");
        // 防御旧 Neo4j 节点缺少 name/id 属性导致的 NPE（MERGE ON CREATE 之前创建的节点）
        if (name == null) {
            name = id != null ? id : "unnamed";
        }
        return new GraphNodeView(
                id != null ? id : "unknown", type,
                name,
                (String) row.get("layer"),
                row.get("granularity") != null ? GraphNodeView.Granularity.valueOf((String) row.get("granularity")) : null,
                (String) row.get("parentId"),
                row.containsKey("attrs") ? (Map<String, Object>) row.get("attrs") : null);
    }

    private static FlowEdgeView mapEdge(Map<String, Object> row) {
        GraphNodeView.Granularity g = row.get("granularity") != null
                ? GraphNodeView.Granularity.valueOf((String) row.get("granularity"))
                : GraphNodeView.Granularity.TABLE;
        return new FlowEdgeView(
                (String) row.get("from"), (String) row.get("to"), g,
                row.get("taskDefId") instanceof Number n ? n.longValue() : null,
                row.get("confidence") != null ? FlowEdgeView.Confidence.valueOf((String) row.get("confidence")) : null,
                row.get("transform") != null ? FlowEdgeView.Transform.valueOf((String) row.get("transform")) : null,
                (String) row.get("source"),
                (String) row.get("humanState"),
                (String) row.get("modelVersion"));
    }

    private static Map<String, Object> params(long tenantId, long projectId, Object... kv) {
        Map<String, Object> p = new HashMap<>();
        p.put("tenantId", tenantId);
        p.put("projectId", projectId);
        for (int i = 0; i < kv.length; i += 2) p.put((String) kv[i], kv[i + 1]);
        return p;
    }

    /**
     * 解析逗号分隔的过滤参数列表。空/空白 → null。
     */
    public static List<String> parseFilterList(String csv) {
        if (csv == null || csv.isBlank()) return null;
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * 构建表节点富化 attrs Map 的 Cypher 字面量（052 D11）。
     * 包含：layer、producers(产出此表的 Task name[])、syncedRowsToday(今日 SYNCED rowCount 合计)、
     * lastSyncDate(最近一次 SYNCED 的 bizDate)。
     */
    private static String tableAttrsCypher() {
        // 054：datasourceId/datasourceName 走 pattern comprehension——兼容写侧 (:Datasource)-[:HAS_TABLE]->(:Table)
        // 与测试 seed (Table)-[:HAS_DATASOURCE]->(Datasource) 两种关系方向（无向匹配 HAS_DATASOURCE|HAS_TABLE）；
        // 任一端无数据源（如孤儿表）→ head([])=null。仅用于表节点（nodeAttrsExpr 的 CASE 已限定）。
        return """
               {
                 layer: end.layer,
                 datasourceId: end.datasourceId,
                 datasourceName: head([(end)-[:HAS_DATASOURCE|HAS_TABLE]-(ds:Datasource) | ds.name]),
                 columnCount: size([(end)-[:HAS_COLUMN]->(col:Column) | col]),
                 producers: [(task:Task)-[:WRITES]->(end) WHERE task.tenantId=$tenantId AND task.projectId=$projectId | task.name],
                 syncedRowsToday: reduce(total=0, rc IN [(run:TaskRun)-[sync:SYNCED]->(end) WHERE run.bizDate=date() | sync.rowCount] | total + rc),
                 lastSyncDate: toString(reduce(m=null, d IN [(run:TaskRun)-[sync:SYNCED]->(end) | run.bizDate] | CASE WHEN m IS NULL OR d > m THEN d ELSE m END))
               }""";
    }

    /**
     * 构建 Cypher WHERE 子句片段用于节点/边过滤（052 D12）。
     * 每个过滤参数可空：{@code $x IS NULL OR ... IN $x}，避免拼 Cypher 注入。
     */
    private static String nodeFilterWhere() {
        return """
               AND ($layers IS NULL OR end.layer IN $layers)
               AND ($types IS NULL OR CASE labels(end)[0] WHEN 'Table' THEN 'TABLE' WHEN 'Column' THEN 'COLUMN' WHEN 'Metric' THEN 'METRIC' ELSE 'TABLE' END IN $types)""";
    }

    private static String edgeFilterWhere() {
        // 置于 `WITH DISTINCT r` 之后 → 必须以 WHERE 起头（此处无前驱 WHERE，
        // 用 AND 会被解析为 `r AND (...)` 即 Relationship AND Boolean 类型错误）。
        return """
               WHERE ($confidences IS NULL OR r.confidence IN $confidences)
               AND ($sources IS NULL OR r.source IN $sources)""";
    }

    /** 构建 attrs：Table 富化，其他保持 {}。 */
    private static String nodeAttrsExpr() {
        return """
               CASE WHEN 'Table' IN labels(end)
                 THEN """ + tableAttrsCypher() + """
                 ELSE {} END""";
    }

    /**
     * 将过滤参数注入 params map（可空）。
     */
    private static void putFilters(Map<String, Object> p, List<String> layers, List<String> types,
                                   List<String> confidences, List<String> sources) {
        p.put("layers", layers);
        p.put("types", types);
        p.put("confidences", confidences);
        p.put("sources", sources);
    }

    // ═════════════════════════════════════════════════════════════
    // US1: 结构下钻
    // ═════════════════════════════════════════════════════════════

    /** 数据源列表（三级树第一层）。T014 */
    public List<GraphNodeView> datasources(long tenantId, long projectId, int offset, int limit) {
        int l = clampLimit(limit);
        String cypher = """
                MATCH (d:Datasource)
                WHERE d.tenantId=$tenantId AND d.projectId=$projectId
                RETURN d.id AS id, 'DATASOURCE' AS type, d.name AS name,
                       d.name AS layer, NULL AS granularity, NULL AS parentId, {} AS attrs
                ORDER BY d.name
                SKIP $offset LIMIT $limit""";
        List<Map<String, Object>> rows = execute(cypher, params(tenantId, projectId,
                "offset", offset, "limit", l));
        return rows.stream().map(LineageQueryService::mapNode).toList();
    }

    /**
     * 054 US3：某数据源下的表列表（分面「数据源」——修 052 占位 bug，展开数据源出真实表而非列）。
     * 走生产结构边 {@code (:Datasource {id})-[:HAS_TABLE]->(:Table)}；表节点复用 {@link #tableAttrsCypher()}
     * 富化（datasourceId/datasourceName/layer/columnCount…）。只读、恒带 tenant/project 隔离。
     */
    public List<GraphNodeView> tablesByDatasource(long tenantId, long projectId, String datasourceId,
                                                  int offset, int limit) {
        int l = clampLimit(limit);
        String cypher = """
                MATCH (d:Datasource {id:$dsId})-[:HAS_TABLE]->(end:Table)
                WHERE d.tenantId=$tenantId AND d.projectId=$projectId
                  AND end.tenantId=$tenantId AND end.projectId=$projectId
                RETURN end.id AS id, 'TABLE' AS type, end.qualifiedName AS name,
                       end.layer AS layer, NULL AS granularity, $dsId AS parentId,
                       """ + tableAttrsCypher() + """
                 AS attrs
                ORDER BY end.qualifiedName
                SKIP $offset LIMIT $limit""";
        List<Map<String, Object>> rows = execute(cypher, params(tenantId, projectId,
                "dsId", datasourceId, "offset", offset, "limit", l));
        return rows.stream().map(LineageQueryService::mapNode).toList();
    }

    /**
     * 054 US3：某分层下的表列表（分面「分层」——ODS/DWD/DWS/ADS 跨数据源聚合）。
     * 表节点复用 {@link #tableAttrsCypher()} 富化（含 datasourceName，便于同层跨库可辨）。
     */
    public List<GraphNodeView> tablesByLayer(long tenantId, long projectId, String layer,
                                             int offset, int limit) {
        int l = clampLimit(limit);
        String cypher = """
                MATCH (end:Table)
                WHERE end.tenantId=$tenantId AND end.projectId=$projectId AND end.layer=$layer
                RETURN end.id AS id, 'TABLE' AS type, end.qualifiedName AS name,
                       end.layer AS layer, NULL AS granularity, NULL AS parentId,
                       """ + tableAttrsCypher() + """
                 AS attrs
                ORDER BY end.qualifiedName
                SKIP $offset LIMIT $limit""";
        List<Map<String, Object>> rows = execute(cypher, params(tenantId, projectId,
                "layer", layer, "offset", offset, "limit", l));
        return rows.stream().map(LineageQueryService::mapNode).toList();
    }

    /** 表的列列表。T015 */
    public List<GraphNodeView> columns(long tenantId, long projectId, String tableId, int offset, int limit) {
        int l = clampLimit(limit);
        String cypher = """
                MATCH (t:Table {id:$tableId})-[:HAS_COLUMN]->(c:Column)
                WHERE t.tenantId=$tenantId AND t.projectId=$projectId
                RETURN c.id AS id, 'COLUMN' AS type, c.name AS name,
                       NULL AS layer, NULL AS granularity, $tableId AS parentId,
                       {dataType: c.dataType, ordinal: c.ordinal,
                        datasourceId: t.datasourceId,
                        datasourceName: head([(t)-[:HAS_DATASOURCE|HAS_TABLE]-(ds:Datasource) | ds.name])} AS attrs
                ORDER BY c.ordinal
                SKIP $offset LIMIT $limit""";
        List<Map<String, Object>> rows = execute(cypher, params(tenantId, projectId,
                "tableId", tableId, "offset", offset, "limit", l));
        return rows.stream().map(LineageQueryService::mapNode).toList();
    }

    /**
     * 052 T038：表节点「展开列」——本表列清单 + 列级派生边（FR-015）。
     *
     * <p>节点集 = 本表列（{@code parentId=本表}）∪ 经 {@code DERIVES_FROM} 1 跳邻接列
     * （{@code parentId=其所属表}）；后者使「列与其上下游列之间的派生边」在两表都展开时能闭合渲染
     * （布局层丢弃悬挂边，故必须带入对端列节点）。边集 = 本表任一列关联的 {@code DERIVES_FROM}（双向）。
     * 只读；neo4j 不可达经 {@link #execute} 降级为 {@code lineage.store_unavailable}。
     */
    public LineageGraph expandColumns(long tenantId, long projectId, String tableId) {
        String nodeCypher = """
                MATCH (t:Table {id:$tableId})-[:HAS_COLUMN]->(c:Column)
                WHERE t.tenantId=$tenantId AND t.projectId=$projectId
                RETURN c.id AS id, 'COLUMN' AS type, c.name AS name,
                       NULL AS layer, 'COLUMN' AS granularity, $tableId AS parentId,
                       {dataType: c.dataType, ordinal: c.ordinal,
                        datasourceId: t.datasourceId,
                        datasourceName: head([(t)-[:HAS_DATASOURCE|HAS_TABLE]-(ds:Datasource) | ds.name])} AS attrs
                LIMIT $limit
                UNION
                MATCH (t:Table {id:$tableId})-[:HAS_COLUMN]->(:Column)-[:DERIVES_FROM]-(nb:Column)<-[:HAS_COLUMN]-(nbt:Table)
                WHERE t.tenantId=$tenantId AND t.projectId=$projectId
                  AND nbt.tenantId=$tenantId AND nbt.projectId=$projectId
                RETURN nb.id AS id, 'COLUMN' AS type, nb.name AS name,
                       NULL AS layer, 'COLUMN' AS granularity, nbt.id AS parentId,
                       {dataType: nb.dataType, ordinal: nb.ordinal,
                        datasourceId: nbt.datasourceId,
                        datasourceName: head([(nbt)-[:HAS_DATASOURCE|HAS_TABLE]-(ds:Datasource) | ds.name])} AS attrs
                LIMIT $limit""";
        List<Map<String, Object>> nodeRows = execute(nodeCypher, params(tenantId, projectId,
                "tableId", tableId, "limit", MAX_NODES));
        List<GraphNodeView> nodes = nodeRows.stream().map(LineageQueryService::mapNode).toList();
        boolean truncated = nodes.size() >= MAX_NODES;
        if (truncated) logTruncation(tableId, 1, nodes.size());

        String edgeCypher = """
                MATCH (t:Table {id:$tableId})-[:HAS_COLUMN]->(:Column)-[r:DERIVES_FROM]-(:Column)
                WHERE t.tenantId=$tenantId AND t.projectId=$projectId
                WITH DISTINCT r
                RETURN startNode(r).id AS from, endNode(r).id AS to, 'COLUMN' AS granularity,
                       r.taskDefId AS taskDefId, r.confidence AS confidence, r.transform AS transform,
                       r.source AS source, r.modelVersion AS modelVersion
                LIMIT $limit""";
        List<Map<String, Object>> edgeRows = execute(edgeCypher, params(tenantId, projectId,
                "tableId", tableId, "limit", MAX_NODES));
        List<FlowEdgeView> edges = annotateCorrections(tenantId, projectId,
                edgeRows.stream().map(LineageQueryService::mapEdge).toList());

        return new LineageGraph(nodes, edges,
                GraphNodeView.Granularity.COLUMN, 1, truncated, truncated ? nodes.size() : null);
    }

    // ═════════════════════════════════════════════════════════════
    // 任务级下游（补数据展开，A1 迁移自 LineageGraphService.downstreamLevels）
    // ═════════════════════════════════════════════════════════════

    /**
     * 某任务的血缘下游任务 + BFS 层级（taskDefId → 层级，首层=1，去重去自身，发现顺序）。
     * 取代 {@code LineageGraphService.downstreamLevels}（读 {@code task_table_io}）。沿
     * {@code :Task-[:WRITES]->:Table} 经 {@code :FLOWS_TO} 变长可达的下游表 → 读这些表的
     * {@code :Task}（含纯消费 leaf）。level = 起点 WRITE 表到下游表的最短 FLOWS_TO 跳数 + 1。
     * 韧性：neo4j 不可达返回空 map（补数据降级为只补自身，不阻断主链路，对标 FR-007）。
     */
    public java.util.LinkedHashMap<Long, Integer> downstreamTaskLevels(long tenantId, long projectId, long taskDefId) {
        try {
            String cypher = """
                    MATCH (start:Task {taskDefId:$taskDefId})
                    WHERE start.tenantId=$tenantId AND start.projectId=$projectId
                    MATCH (start)-[:WRITES]->(wt:Table)
                    MATCH path = (wt)-[:FLOWS_TO*0..20]->(downTable:Table)
                    MATCH (dt:Task)-[:READS]->(downTable)
                    WHERE dt.tenantId=$tenantId AND dt.projectId=$projectId AND dt.taskDefId <> $taskDefId
                    WITH dt, min(length(path)) + 1 AS level
                    RETURN dt.taskDefId AS taskDefId, level
                    ORDER BY level, dt.taskDefId""";
            List<Map<String, Object>> rows = execute(cypher, params(tenantId, projectId, "taskDefId", taskDefId));
            java.util.LinkedHashMap<Long, Integer> out = new java.util.LinkedHashMap<>();
            for (Map<String, Object> r : rows) {
                out.put(((Number) r.get("taskDefId")).longValue(), ((Number) r.get("level")).intValue());
            }
            return out;
        } catch (Exception e) {
            log.warn("downstream task query degraded (neo4j unreachable), returning empty: {}", e.toString());
            return new java.util.LinkedHashMap<>();
        }
    }

    // ═════════════════════════════════════════════════════════════
    // US2: 上下游 + 影响面（变长路径）
    // ═════════════════════════════════════════════════════════════

    /**
     * 表级上游（变长路径）。
     *
     * @param granularity TABLE（仅 FLOWS_TO）或 COLUMN（含 DERIVES_FROM）
     * @param layers      可空层过滤（ODS/DWD/DWS/ADS）
     * @param types       可空类型过滤（TABLE/COLUMN/METRIC）
     * @param confidences 可空置信度过滤（CONFIRMED/UNVERIFIED/CONFLICT/DECLARED）
     * @param sources     可空来源过滤（SQL_PARSED/FORM/AGENT/…）
     */
    public LineageGraph upstream(long tenantId, long projectId, String tableId, int depth,
                                  GraphNodeView.Granularity granularity,
                                  List<String> layers, List<String> types,
                                  List<String> confidences, List<String> sources) {
        return traverse(tenantId, projectId, tableId, depth, granularity, true,
                layers, types, confidences, sources);
    }

    /** 表级下游（变长路径）。 */
    public LineageGraph downstream(long tenantId, long projectId, String tableId, int depth,
                                    GraphNodeView.Granularity granularity,
                                    List<String> layers, List<String> types,
                                    List<String> confidences, List<String> sources) {
        return traverse(tenantId, projectId, tableId, depth, granularity, false,
                layers, types, confidences, sources);
    }

    private LineageGraph traverse(long tenantId, long projectId, String tableId, int depth,
                                   GraphNodeView.Granularity granularity, boolean upstream,
                                   List<String> layers, List<String> types,
                                   List<String> confidences, List<String> sources) {
        int d = clampDepth(depth);
        String relPattern = granularity == GraphNodeView.Granularity.COLUMN
                ? "FLOWS_TO|DERIVES_FROM" : "FLOWS_TO";
        String dir = upstream ? "<-" : "-";
        String arrow = upstream ? "-" : "->";

        String cypher = String.format("""
                MATCH path = (start {id:$tableId})%s[:%s*1..%d]%s(end)
                WHERE start.tenantId=$tenantId AND start.projectId=$projectId
                  AND end.tenantId=$tenantId AND end.projectId=$projectId
                  %s
                RETURN DISTINCT end.id AS id,
                       CASE labels(end)[0]
                         WHEN 'Table' THEN 'TABLE'
                         WHEN 'Column' THEN 'COLUMN'
                         WHEN 'Metric' THEN 'METRIC'
                         ELSE 'TABLE'
                       END AS type,
                       COALESCE(end.name, end.qualifiedName) AS name,
                       end.layer AS layer,
                       '%s' AS granularity,
                       NULL AS parentId,
                       %s AS attrs
                LIMIT $limit""",
                dir, relPattern, d, arrow,
                nodeFilterWhere(),
                granularity == GraphNodeView.Granularity.COLUMN ? "COLUMN" : "TABLE",
                nodeAttrsExpr());

        Map<String, Object> p = params(tenantId, projectId,
                "tableId", tableId, "limit", MAX_NODES);
        putFilters(p, layers, types, confidences, sources);

        List<Map<String, Object>> rows = execute(cypher, p);

        List<GraphNodeView> nodes = rows.stream().map(LineageQueryService::mapNode).toList();
        boolean truncated = nodes.size() >= MAX_NODES;
        if (truncated) logTruncation(tableId, d, nodes.size());

        // 节点 id 集合（用于边闭合过滤悬挂边）
        Set<String> nodeIds = nodes.stream().map(GraphNodeView::id).collect(Collectors.toSet());

        // 041：补真实边投影 + 052 边过滤在 annotateCorrections 之后
        String edgeCypher = String.format("""
                MATCH path = (start {id:$tableId})%s[:%s*1..%d]%s(end)
                WHERE start.tenantId=$tenantId AND start.projectId=$projectId
                UNWIND relationships(path) AS r
                WITH DISTINCT r
                %s
                RETURN startNode(r).id AS from, endNode(r).id AS to,
                       CASE type(r) WHEN 'DERIVES_FROM' THEN 'COLUMN' ELSE 'TABLE' END AS granularity,
                       r.taskDefId AS taskDefId, r.confidence AS confidence, r.transform AS transform,
                       r.source AS source, r.modelVersion AS modelVersion
                LIMIT $limit""", dir, relPattern, d, arrow,
                edgeFilterWhere());
        Map<String, Object> ep = params(tenantId, projectId,
                "tableId", tableId, "limit", MAX_NODES);
        putFilters(ep, null, null, confidences, sources); // only confidence/source for edges
        List<Map<String, Object>> edgeRows = execute(edgeCypher, ep);
        List<FlowEdgeView> edges = annotateCorrections(tenantId, projectId,
                edgeRows.stream().map(LineageQueryService::mapEdge).toList());

        // 闭合于节点集：剔除悬挂边（from 或 to 不在节点集中的边）
        edges = edges.stream()
                .filter(e -> nodeIds.contains(e.from()) && nodeIds.contains(e.to()))
                .toList();

        return new LineageGraph(nodes, edges,
                granularity, d, truncated, truncated ? nodes.size() : null);
    }

    /** 列级上游。 */
    public LineageGraph columnUpstream(long tenantId, long projectId, String columnId, int depth,
                                       List<String> confidences, List<String> sources) {
        return columnTraverse(tenantId, projectId, columnId, depth, true, confidences, sources);
    }

    /** 列级下游。 */
    public LineageGraph columnDownstream(long tenantId, long projectId, String columnId, int depth,
                                         List<String> confidences, List<String> sources) {
        return columnTraverse(tenantId, projectId, columnId, depth, false, confidences, sources);
    }

    private LineageGraph columnTraverse(long tenantId, long projectId, String columnId,
                                         int depth, boolean upstream,
                                         List<String> confidences, List<String> sources) {
        int d = clampDepth(depth);
        String dir = upstream ? "<-" : "-";
        String arrow = upstream ? "-" : "->";

        String cypher = String.format("""
                MATCH path = (start:Column {id:$columnId})%s[:DERIVES_FROM*1..%d]%s(end:Column)
                WHERE start.tenantId=$tenantId AND start.projectId=$projectId
                RETURN DISTINCT end.id AS id, 'COLUMN' AS type, end.name AS name,
                       NULL AS layer, 'COLUMN' AS granularity, NULL AS parentId,
                       {dataType: end.dataType, ordinal: end.ordinal,
                        datasourceId: head([(end)<-[:HAS_COLUMN]-(ct:Table) | ct.datasourceId]),
                        datasourceName: head([(end)<-[:HAS_COLUMN]-(ct:Table)-[:HAS_DATASOURCE|HAS_TABLE]-(ds:Datasource) | ds.name])} AS attrs
                LIMIT $limit""",
                dir, d, arrow);

        List<Map<String, Object>> rows = execute(cypher, params(tenantId, projectId,
                "columnId", columnId, "limit", MAX_NODES));

        List<GraphNodeView> nodes = rows.stream().map(LineageQueryService::mapNode).toList();
        boolean truncated = nodes.size() >= MAX_NODES;
        if (truncated) logTruncation(columnId, d, nodes.size());

        // 列级边投影 + 边过滤
        String edgeCypher = String.format("""
                MATCH path = (start:Column {id:$columnId})%s[:DERIVES_FROM*1..%d]%s(end:Column)
                WHERE start.tenantId=$tenantId AND start.projectId=$projectId
                UNWIND relationships(path) AS r
                WITH DISTINCT r
                %s
                RETURN startNode(r).id AS from, endNode(r).id AS to,
                       'COLUMN' AS granularity,
                       r.taskDefId AS taskDefId, r.confidence AS confidence, r.transform AS transform,
                       r.source AS source, r.modelVersion AS modelVersion
                LIMIT $limit""", dir, d, arrow, edgeFilterWhere());
        Map<String, Object> ep = params(tenantId, projectId,
                "columnId", columnId, "limit", MAX_NODES);
        putFilters(ep, null, null, confidences, sources);
        List<Map<String, Object>> edgeRows = execute(edgeCypher, ep);
        List<FlowEdgeView> edges = annotateCorrections(tenantId, projectId,
                edgeRows.stream().map(LineageQueryService::mapEdge).toList());

        return new LineageGraph(nodes, edges,
                GraphNodeView.Granularity.COLUMN, d, truncated, truncated ? nodes.size() : null);
    }

    // ═════════════════════════════════════════════════════════════
    // 052 邻域（双向带边，US1 / FR-003/007）
    // ═════════════════════════════════════════════════════════════

    /**
     * 双向子图：无向遍历同时取上下游节点 + 边（052 D9）。
     *
     * @param granularity TABLE（仅 FLOWS_TO）或 COLUMN（含 DERIVES_FROM）
     * @param layers      可空层过滤
     * @param types       可空类型过滤
     * @param confidences 可空置信度过滤（边）
     * @param sources     可空来源过滤（边）
     */
    public LineageGraph neighborhood(long tenantId, long projectId, String tableId, int depth,
                                      GraphNodeView.Granularity granularity,
                                      List<String> layers, List<String> types,
                                      List<String> confidences, List<String> sources) {
        int d = clampDepth(depth);
        String relPattern = granularity == GraphNodeView.Granularity.COLUMN
                ? "FLOWS_TO|DERIVES_FROM" : "FLOWS_TO";

        // 无向遍历：锚点 + 所有可达邻居（distinct）
        String cypher = String.format("""
                MATCH path = (start {id:$tableId})-[:%s*1..%d]-(end)
                WHERE start.tenantId=$tenantId AND start.projectId=$projectId
                  AND end.tenantId=$tenantId AND end.projectId=$projectId
                  %s
                RETURN DISTINCT end.id AS id,
                       CASE labels(end)[0]
                         WHEN 'Table' THEN 'TABLE'
                         WHEN 'Column' THEN 'COLUMN'
                         WHEN 'Metric' THEN 'METRIC'
                         ELSE 'TABLE'
                       END AS type,
                       COALESCE(end.name, end.qualifiedName) AS name,
                       end.layer AS layer,
                       '%s' AS granularity,
                       NULL AS parentId,
                       %s AS attrs
                LIMIT $limit""",
                relPattern, d,
                nodeFilterWhere(),
                granularity == GraphNodeView.Granularity.COLUMN ? "COLUMN" : "TABLE",
                nodeAttrsExpr());

        Map<String, Object> p = params(tenantId, projectId,
                "tableId", tableId, "limit", MAX_NODES);
        putFilters(p, layers, types, confidences, sources);
        List<Map<String, Object>> rows = execute(cypher, p);

        List<GraphNodeView> nodes = rows.stream().map(LineageQueryService::mapNode).toList();
        // 锚点本身可能不在结果中（无向匹配不包含自身），加回锚点
        boolean hasAnchor = nodes.stream().anyMatch(n -> n.id().equals(tableId));
        if (!hasAnchor) {
            // 查找锚点（变量名用 end 以复用 nodeAttrsExpr——锚点表须带 columnCount 等富属性，
            // 否则焦点表的展开列 chevron 因 attrs 空而不显示）
            String anchorCypher = ("""
                    MATCH (end {id:$tableId})
                    WHERE end.tenantId=$tenantId AND end.projectId=$projectId
                    RETURN end.id AS id,
                           CASE labels(end)[0]
                             WHEN 'Table' THEN 'TABLE'
                             WHEN 'Column' THEN 'COLUMN'
                             WHEN 'Metric' THEN 'METRIC'
                             ELSE 'TABLE'
                           END AS type,
                           COALESCE(end.name, end.qualifiedName) AS name,
                           end.layer AS layer,
                           '%s' AS granularity,
                           NULL AS parentId, %s AS attrs
                    LIMIT 1""").formatted(
                    granularity == GraphNodeView.Granularity.COLUMN ? "COLUMN" : "TABLE",
                    nodeAttrsExpr());
            List<Map<String, Object>> aRows = execute(anchorCypher, params(tenantId, projectId, "tableId", tableId));
            if (!aRows.isEmpty()) {
                List<GraphNodeView> withAnchor = new ArrayList<>();
                withAnchor.add(mapNode(aRows.get(0)));
                withAnchor.addAll(nodes);
                nodes = withAnchor;
            }
        }

        boolean truncated = nodes.size() >= MAX_NODES;
        if (truncated) logTruncation(tableId, d, nodes.size());

        // 边投影：无向遍历取边（distinct）
        Set<String> nodeIds = nodes.stream().map(GraphNodeView::id).collect(Collectors.toSet());
        String edgeCypher = String.format("""
                MATCH path = (start {id:$tableId})-[:%s*1..%d]-(end)
                WHERE start.tenantId=$tenantId AND start.projectId=$projectId
                UNWIND relationships(path) AS r
                WITH DISTINCT r
                %s
                RETURN startNode(r).id AS from, endNode(r).id AS to,
                       CASE type(r) WHEN 'DERIVES_FROM' THEN 'COLUMN' ELSE 'TABLE' END AS granularity,
                       r.taskDefId AS taskDefId, r.confidence AS confidence, r.transform AS transform,
                       r.source AS source, r.modelVersion AS modelVersion
                LIMIT $limit""", relPattern, d, edgeFilterWhere());
        Map<String, Object> ep = params(tenantId, projectId,
                "tableId", tableId, "limit", MAX_NODES);
        putFilters(ep, null, null, confidences, sources);
        List<Map<String, Object>> edgeRows = execute(edgeCypher, ep);
        List<FlowEdgeView> edges = annotateCorrections(tenantId, projectId,
                edgeRows.stream().map(LineageQueryService::mapEdge).toList());

        // 闭合于节点集
        edges = edges.stream()
                .filter(e -> nodeIds.contains(e.from()) && nodeIds.contains(e.to()))
                .toList();

        return new LineageGraph(nodes, edges, granularity, d, truncated, truncated ? nodes.size() : null);
    }

    // ═════════════════════════════════════════════════════════════
    // 052 影响面（边填充 + 真实可达总数，US3 / FR-012/013）
    // ═════════════════════════════════════════════════════════════

    /**
     * 影响面：全下游可达（表+列）。052 扩展：填充 edges + reachableTotal + totalIsLowerBound。
     * {@code [:FLOWS_TO|DERIVES_FROM*]} 闭包。
     */
    public ImpactResult impact(long tenantId, long projectId, String nodeId,
                                int depth, int offset, int limit,
                                List<String> layers, List<String> types,
                                List<String> confidences, List<String> sources) {
        int d = clampDepth(depth);
        int l = clampLimit(limit);

        // 起点节点
        String rootCypher = """
                MATCH (n {id:$nodeId})
                WHERE n.tenantId=$tenantId AND n.projectId=$projectId
                RETURN n.id AS id,
                       CASE labels(n)[0]
                         WHEN 'Table' THEN 'TABLE'
                         WHEN 'Column' THEN 'COLUMN'
                         WHEN 'Metric' THEN 'METRIC'
                         ELSE 'TABLE'
                       END AS type,
                       COALESCE(n.name, n.qualifiedName) AS name,
                       n.layer AS layer,
                       NULL AS granularity, NULL AS parentId, {} AS attrs
                LIMIT 1""";
        List<Map<String, Object>> rootRows = execute(rootCypher, params(tenantId, projectId, "nodeId", nodeId));
        GraphNodeView root = rootRows.isEmpty() ? null : mapNode(rootRows.get(0));
        if (root == null) {
            return new ImpactResult(
                    new GraphNodeView(nodeId, GraphNodeView.NodeType.TABLE, nodeId, null),
                    List.of(), List.of(), 0, 0, false, false, null);
        }

        // 全下游闭包（分页，带节点过滤）
        String cypher = String.format("""
                MATCH (start {id:$nodeId})-[:FLOWS_TO|DERIVES_FROM*1..%d]->(end)
                WHERE start.tenantId=$tenantId AND start.projectId=$projectId
                  AND end.tenantId=$tenantId AND end.projectId=$projectId
                  %s
                RETURN DISTINCT end.id AS id,
                       CASE labels(end)[0]
                         WHEN 'Table' THEN 'TABLE'
                         WHEN 'Column' THEN 'COLUMN'
                         WHEN 'Metric' THEN 'METRIC'
                         ELSE 'TABLE'
                       END AS type,
                       COALESCE(end.name, end.qualifiedName) AS name,
                       end.layer AS layer,
                       CASE WHEN 'Column' IN labels(end) THEN 'COLUMN' ELSE 'TABLE' END AS granularity,
                       NULL AS parentId,
                       %s AS attrs
                SKIP $offset LIMIT $limit""", d, nodeFilterWhere(), nodeAttrsExpr());

        Map<String, Object> p = params(tenantId, projectId,
                "nodeId", nodeId, "offset", offset, "limit", l);
        putFilters(p, layers, types, confidences, sources);
        List<Map<String, Object>> rows = execute(cypher, p);
        List<GraphNodeView> downstream = rows.stream().map(LineageQueryService::mapNode).toList();
        boolean truncated = downstream.size() >= l;
        if (truncated) logTruncation(nodeId, d, downstream.size());

        // 独立 COUNT：真实下游可达总数（与分页解耦 + countCap 保护）
        String countCypher = String.format("""
                MATCH (start {id:$nodeId})-[:FLOWS_TO|DERIVES_FROM*1..%d]->(end)
                WHERE start.tenantId=$tenantId AND start.projectId=$projectId
                  AND end.tenantId=$tenantId AND end.projectId=$projectId
                  %s
                WITH DISTINCT end
                LIMIT $countCap
                RETURN count(end) AS total""", d, nodeFilterWhere());
        Map<String, Object> cp = params(tenantId, projectId,
                "nodeId", nodeId, "countCap", COUNT_CAP);
        putFilters(cp, layers, types, null, null);
        List<Map<String, Object>> countRows = execute(countCypher, cp);
        long rawTotal = countRows.isEmpty() ? 0 : ((Number) countRows.get(0).get("total")).longValue();
        int reachableTotal = (int) Math.min(rawTotal, COUNT_CAP);
        boolean totalIsLowerBound = rawTotal >= COUNT_CAP;

        // 边投影（闭合于当前页下游集，经 annotateCorrections 剔 REMOVED）
        Set<String> nodeIds = downstream.stream().map(GraphNodeView::id).collect(Collectors.toSet());
        nodeIds.add(root.id()); // 包含根节点（边的起点可能是根）
        String edgeCypher = String.format("""
                MATCH path = (start {id:$nodeId})-[:FLOWS_TO|DERIVES_FROM*1..%d]->(end)
                WHERE start.tenantId=$tenantId AND start.projectId=$projectId
                UNWIND relationships(path) AS r
                WITH DISTINCT r
                %s
                RETURN startNode(r).id AS from, endNode(r).id AS to,
                       CASE type(r) WHEN 'DERIVES_FROM' THEN 'COLUMN' ELSE 'TABLE' END AS granularity,
                       r.taskDefId AS taskDefId, r.confidence AS confidence, r.transform AS transform,
                       r.source AS source, r.modelVersion AS modelVersion
                LIMIT $limit""", d, edgeFilterWhere());
        Map<String, Object> ep = params(tenantId, projectId,
                "nodeId", nodeId, "limit", MAX_NODES);
        putFilters(ep, null, null, confidences, sources);
        List<Map<String, Object>> edgeRows = execute(edgeCypher, ep);
        List<FlowEdgeView> edges = annotateCorrections(tenantId, projectId,
                edgeRows.stream().map(LineageQueryService::mapEdge).toList());

        // 闭合于节点集
        edges = edges.stream()
                .filter(e -> nodeIds.contains(e.from()) && nodeIds.contains(e.to()))
                .toList();

        return new ImpactResult(root, downstream, edges,
                downstream.size(), reachableTotal, totalIsLowerBound,
                truncated, truncated ? downstream.size() : null);
    }

    // ═════════════════════════════════════════════════════════════
    // 052 按名搜索（US2 / FR-008/009/011/022）
    // ═════════════════════════════════════════════════════════════

    /**
     * 按名搜索数据资产。MVP toLower CONTAINS 中缀匹配（Table→qualifiedName、Column→name、
     * Metric→name），多标签 UNION，YIELD 后强制 tenant/project 隔离。
     *
     * @param types 可空类型过滤（TABLE/COLUMN/METRIC），null/空 = 全部
     */
    public List<SearchCandidate> search(long tenantId, long projectId, String keyword,
                                        List<String> types, int offset, int limit) {
        int l = clampSearchLimit(limit);
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty()) {
            return List.of();
        }

        boolean includeTable = types == null || types.isEmpty() || types.contains("TABLE");
        boolean includeColumn = types == null || types.isEmpty() || types.contains("COLUMN");
        boolean includeMetric = types == null || types.isEmpty() || types.contains("METRIC");

        List<SearchCandidate> results = new ArrayList<>();

        if (includeTable) {
            // 054：join 所属数据源展示名（兼容写侧 (:Datasource)-[:HAS_TABLE]->(:Table) 与
            // 测试 seed (Table)-[:HAS_DATASOURCE]->(Datasource)；任一缺失 head([])=null）。
            String tableCypher = """
                    MATCH (t:Table)
                    WHERE t.tenantId=$tenantId AND t.projectId=$projectId
                      AND toLower(t.qualifiedName) CONTAINS toLower($keyword)
                    RETURN t.id AS id, 'TABLE' AS type,
                           t.qualifiedName AS name, t.layer AS layer,
                           t.datasourceId AS datasource,
                           head([(t)-[:HAS_DATASOURCE|HAS_TABLE]-(dd:Datasource) | dd.name]) AS datasourceName
                    ORDER BY t.qualifiedName
                    SKIP $offset LIMIT $limit""";
            List<Map<String, Object>> rows = execute(tableCypher, params(tenantId, projectId,
                    "keyword", kw, "offset", offset, "limit", l));
            rows.forEach(r -> results.add(new SearchCandidate(
                    (String) r.get("id"), (String) r.get("type"), (String) r.get("name"),
                    (String) r.get("layer"), (String) r.get("datasource"), (String) r.get("datasourceName"))));
        }

        if (includeColumn) {
            // 054：列继承所属表的数据源展示名（经 HAS_COLUMN→Table→Datasource）。
            String colCypher = """
                    MATCH (c:Column)
                    WHERE c.tenantId=$tenantId AND c.projectId=$projectId
                      AND toLower(c.name) CONTAINS toLower($keyword)
                    RETURN c.id AS id, 'COLUMN' AS type,
                           c.name AS name, NULL AS layer,
                           c.tableKey AS datasource,
                           head([(c)<-[:HAS_COLUMN]-(ct:Table)-[:HAS_DATASOURCE|HAS_TABLE]-(dd:Datasource) | dd.name]) AS datasourceName
                    ORDER BY c.name
                    SKIP $offset LIMIT $limit""";
            List<Map<String, Object>> rows = execute(colCypher, params(tenantId, projectId,
                    "keyword", kw, "offset", offset, "limit", l));
            rows.forEach(r -> results.add(new SearchCandidate(
                    (String) r.get("id"), (String) r.get("type"), (String) r.get("name"),
                    (String) r.get("layer"), (String) r.get("datasource"), (String) r.get("datasourceName"))));
        }

        if (includeMetric) {
            // 054：指标无物理数据源 → datasourceName 恒 null（FR-006/011）。
            String metricCypher = """
                    MATCH (m:Metric)
                    WHERE m.tenantId=$tenantId AND m.projectId=$projectId
                      AND toLower(m.name) CONTAINS toLower($keyword)
                    RETURN m.id AS id, 'METRIC' AS type,
                           m.name AS name, NULL AS layer,
                           m.metricType AS datasource, NULL AS datasourceName
                    ORDER BY m.name
                    SKIP $offset LIMIT $limit""";
            List<Map<String, Object>> rows = execute(metricCypher, params(tenantId, projectId,
                    "keyword", kw, "offset", offset, "limit", l));
            rows.forEach(r -> results.add(new SearchCandidate(
                    (String) r.get("id"), (String) r.get("type"), (String) r.get("name"),
                    (String) r.get("layer"), (String) r.get("datasource"), (String) r.get("datasourceName"))));
        }

        // 排序 + 截断
        results.sort(Comparator.comparing(SearchCandidate::name));
        if (results.size() > l) {
            return results.subList(0, l);
        }
        return results;
    }

    // ═════════════════════════════════════════════════════════════
    // 052 两点间路径（US3 / FR-014）
    // ═════════════════════════════════════════════════════════════

    /**
     * 两点间所有连接路径高亮。有界变长 + pathCap，节点∪边去重，无路径 pathExists=false。
     */
    public LineagePath pathsBetween(long tenantId, long projectId, String fromId, String toId, int depth) {
        int d = clampDepth(depth);

        // 查找端点
        Map<String, Object> ep = params(tenantId, projectId, "fromId", fromId, "toId", toId);
        String endpointCypher = """
                MATCH (n)
                WHERE n.id IN [$fromId, $toId]
                  AND n.tenantId=$tenantId AND n.projectId=$projectId
                RETURN n.id AS id,
                       CASE labels(n)[0]
                         WHEN 'Table' THEN 'TABLE'
                         WHEN 'Column' THEN 'COLUMN'
                         WHEN 'Metric' THEN 'METRIC'
                         ELSE 'TABLE'
                       END AS type,
                       COALESCE(n.name, n.qualifiedName) AS name,
                       n.layer AS layer,
                       NULL AS granularity, NULL AS parentId, {} AS attrs""";
        List<Map<String, Object>> endpointRows = execute(endpointCypher, ep);
        Map<String, GraphNodeView> endpoints = new HashMap<>();
        for (var row : endpointRows) {
            endpoints.put((String) row.get("id"), mapNode(row));
        }
        GraphNodeView fromNode = endpoints.getOrDefault(fromId,
                new GraphNodeView(fromId, GraphNodeView.NodeType.TABLE, fromId, null));
        GraphNodeView toNode = endpoints.getOrDefault(toId,
                new GraphNodeView(toId, GraphNodeView.NodeType.TABLE, toId, null));

        // 有向路径：from → to（*1..depth）
        String pathCypher = String.format("""
                MATCH path = (start {id:$fromId})-[:FLOWS_TO|DERIVES_FROM*1..%d]->(end {id:$toId})
                WHERE start.tenantId=$tenantId AND start.projectId=$projectId
                  AND end.tenantId=$tenantId AND end.projectId=$projectId
                RETURN path
                LIMIT $pathCap""", d);

        Map<String, Object> pp = params(tenantId, projectId,
                "fromId", fromId, "toId", toId, "pathCap", PATH_CAP);
        List<Map<String, Object>> pathRows = execute(pathCypher, pp);

        boolean pathExists = !pathRows.isEmpty();
        boolean truncated = pathRows.size() >= PATH_CAP;

        Set<String> nodeIds = new LinkedHashSet<>();
        List<FlowEdgeView> allEdges = new ArrayList<>();

        for (var row : pathRows) {
            Object pathObj = row.get("path");
            if (pathObj instanceof org.neo4j.driver.types.Path neoPath) {
                // Collect node ids from path
                java.util.List<org.neo4j.driver.types.Node> pathNodeList = new ArrayList<>();
                for (var node : neoPath.nodes()) {
                    pathNodeList.add(node);
                    nodeIds.add(node.get("id").asString());
                }
                // Collect edges: nodes[i] --rel[i]--> nodes[i+1]
                java.util.List<org.neo4j.driver.types.Relationship> relList = new ArrayList<>();
                for (var rel : neoPath.relationships()) {
                    relList.add(rel);
                }
                for (int i = 0; i < relList.size(); i++) {
                    var rel = relList.get(i);
                    String from = pathNodeList.get(i).get("id").asString();
                    String to = pathNodeList.get(i + 1).get("id").asString();
                    FlowEdgeView edge = new FlowEdgeView(from, to,
                            "DERIVES_FROM".equals(rel.type())
                                    ? GraphNodeView.Granularity.COLUMN
                                    : GraphNodeView.Granularity.TABLE,
                            rel.containsKey("taskDefId") ? rel.get("taskDefId").asLong() : null,
                            rel.containsKey("confidence")
                                    ? FlowEdgeView.Confidence.valueOf(rel.get("confidence").asString())
                                    : null,
                            rel.containsKey("transform")
                                    ? FlowEdgeView.Transform.valueOf(rel.get("transform").asString())
                                    : null,
                            rel.containsKey("source") ? rel.get("source").asString() : null,
                            null,
                            rel.containsKey("modelVersion") ? rel.get("modelVersion").asString() : null);
                    allEdges.add(edge);
                }
            }
        }

        // 边去重（按 from+to+granularity 键去重）
        Set<String> edgeKeys = new LinkedHashSet<>();
        List<FlowEdgeView> dedupEdges = new ArrayList<>();
        for (FlowEdgeView e : allEdges) {
            String key = e.from() + "|" + e.to() + "|" + e.granularity();
            if (edgeKeys.add(key)) {
                dedupEdges.add(e);
            }
        }

        // 路径上节点去重并获取详情
        List<GraphNodeView> pathNodes = new ArrayList<>();
        for (String nid : nodeIds) {
            String nodeCypher = """
                    MATCH (n {id:$nid})
                    WHERE n.tenantId=$tenantId AND n.projectId=$projectId
                    RETURN n.id AS id,
                           CASE labels(n)[0]
                             WHEN 'Table' THEN 'TABLE'
                             WHEN 'Column' THEN 'COLUMN'
                             WHEN 'Metric' THEN 'METRIC'
                             ELSE 'TABLE'
                           END AS type,
                           COALESCE(n.name, n.qualifiedName) AS name,
                           n.layer AS layer,
                           NULL AS granularity, NULL AS parentId, {} AS attrs
                    LIMIT 1""";
            List<Map<String, Object>> nRows = execute(nodeCypher, params(tenantId, projectId, "nid", nid));
            if (!nRows.isEmpty()) {
                pathNodes.add(mapNode(nRows.get(0)));
            }
        }

        return new LineagePath(fromNode, toNode, pathNodes, dedupEdges, pathExists, truncated);
    }

    // ═════════════════════════════════════════════════════════════
    // US3: 指标血缘 + 运行态
    // ═════════════════════════════════════════════════════════════

    /** 指标血缘：COMPUTED_FROM 的表/列。按 (metricType, metricId) 查（不依赖 metricKey 合成键）。 */
    public MetricLineage metricLineage(long tenantId, long projectId, String metricType, long metricId) {
        // 指标节点
        String metricCypher = """
                MATCH (m:Metric)
                WHERE m.tenantId=$tenantId AND m.projectId=$projectId
                  AND m.metricType=$metricType AND m.metricId=$metricId
                RETURN m.id AS id, 'METRIC' AS type, m.name AS name,
                       NULL AS layer, NULL AS granularity, NULL AS parentId,
                       {metricType: m.metricType} AS attrs
                LIMIT 1""";
        List<Map<String, Object>> mRows = execute(metricCypher, params(tenantId, projectId,
                "metricType", metricType, "metricId", metricId));
        GraphNodeView metric = mRows.isEmpty()
                ? new GraphNodeView(String.valueOf(metricId), GraphNodeView.NodeType.METRIC, String.valueOf(metricId), null)
                : mapNode(mRows.get(0));

        // 来源表/列
        String cypher = """
                MATCH (m:Metric)-[:COMPUTED_FROM]->(s)
                WHERE m.tenantId=$tenantId AND m.projectId=$projectId
                  AND m.metricType=$metricType AND m.metricId=$metricId
                RETURN s.id AS id,
                       CASE labels(s)[0]
                         WHEN 'Table' THEN 'TABLE'
                         WHEN 'Column' THEN 'COLUMN'
                         ELSE 'TABLE'
                       END AS type,
                       COALESCE(s.name, s.qualifiedName) AS name,
                       s.layer AS layer,
                       NULL AS granularity, NULL AS parentId, {} AS attrs""";
        List<Map<String, Object>> rows = execute(cypher, params(tenantId, projectId,
                "metricType", metricType, "metricId", metricId));
        List<GraphNodeView> sources = rows.stream().map(LineageQueryService::mapNode).toList();

        return new MetricLineage(metric, sources, List.of());
    }

    /** 今日同步行数聚合。 */
    public SyncSummary syncSummary(long tenantId, long projectId) {
        String cypher = """
                MATCH (r:TaskRun)-[s:SYNCED]->(t:Table)
                WHERE r.tenantId=$tenantId AND r.projectId=$projectId
                  AND r.bizDate=date()
                RETURN SUM(s.rowCount) AS syncedRows""";
        List<Map<String, Object>> rows = execute(cypher, params(tenantId, projectId));
        if (rows.isEmpty() || rows.get(0).get("syncedRows") == null) {
            return SyncSummary.empty();
        }
        Object val = rows.get(0).get("syncedRows");
        long syncedRows = val instanceof Number n ? n.longValue() : 0L;
        return new SyncSummary(syncedRows);
    }
}
