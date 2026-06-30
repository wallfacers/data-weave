package com.dataweave.master.application;

import com.dataweave.master.i18n.BizException;
import com.dataweave.master.lineage.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Cypher 变长路径血缘查询服务 —— 取代 {@link LineageGraphService} 中 JDBC 内存 BFS
 * 的读侧方法，运行在 018 提供的只读 neo4j 会话上。
 *
 * <h3>有界查询（FR-004 / SC-004）</h3>
 * 深度上界 {@link #MAX_DEPTH}=20、节点上界 {@link #MAX_NODES}=2000、分页默认 100；
 * 触上界 → truncated=true + log.warn，不静默丢。
 *
 * <h3>租户隔离（FR-003）</h3>
 * 所有查询入参带 (tenantId, projectId)，Cypher WHERE 过滤；默认 1/1，预留 TenantContext。
 */
@Service
public class LineageQueryService {

    private static final Logger log = LoggerFactory.getLogger(LineageQueryService.class);

    public static final int MAX_DEPTH = 20;
    public static final int MAX_NODES = 2000;
    public static final int DEFAULT_LIMIT = 100;

    private final LineageGraphReader reader;

    public LineageQueryService(LineageGraphReader reader) {
        this.reader = reader;
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
            if (exName.contains("ServiceUnavailable") || exName.contains("SessionExpired")
                    || exName.contains("Connect") || exName.contains("neo4j")) {
                throw storeUnavailable(e);
            }
            throw new RuntimeException("Lineage query failed: " + e.getMessage(), e);
        }
    }

    // ─── 行→视图映射辅助 ──────────────────────────────────────

    private static GraphNodeView mapNode(Map<String, Object> row) {
        GraphNodeView.NodeType type = GraphNodeView.NodeType.valueOf((String) row.get("type"));
        return new GraphNodeView(
                (String) row.get("id"), type,
                (String) row.get("name"),
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
                row.get("transform") != null ? FlowEdgeView.Transform.valueOf((String) row.get("transform")) : null);
    }

    private static Map<String, Object> params(long tenantId, long projectId, Object... kv) {
        Map<String, Object> p = new HashMap<>();
        p.put("tenantId", tenantId);
        p.put("projectId", projectId);
        for (int i = 0; i < kv.length; i += 2) p.put((String) kv[i], kv[i + 1]);
        return p;
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

    /** 表的列列表。T015 */
    public List<GraphNodeView> columns(long tenantId, long projectId, String tableId, int offset, int limit) {
        int l = clampLimit(limit);
        String cypher = """
                MATCH (t:Table {id:$tableId})-[:HAS_COLUMN]->(c:Column)
                WHERE t.tenantId=$tenantId AND t.projectId=$projectId
                RETURN c.id AS id, 'COLUMN' AS type, c.name AS name,
                       NULL AS layer, NULL AS granularity, $tableId AS parentId,
                       {dataType: c.dataType, ordinal: c.ordinal} AS attrs
                ORDER BY c.ordinal
                SKIP $offset LIMIT $limit""";
        List<Map<String, Object>> rows = execute(cypher, params(tenantId, projectId,
                "tableId", tableId, "offset", offset, "limit", l));
        return rows.stream().map(LineageQueryService::mapNode).toList();
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
     * 表级上游（变长路径）。T024
     *
     * @param granularity TABLE（仅 FLOWS_TO）或 COLUMN（含 DERIVES_FROM）
     */
    public LineageGraph upstream(long tenantId, long projectId, String tableId,
                                  int depth, GraphNodeView.Granularity granularity) {
        return traverse(tenantId, projectId, tableId, depth, granularity, true);
    }

    /** 表级下游（变长路径）。T024 */
    public LineageGraph downstream(long tenantId, long projectId, String tableId,
                                    int depth, GraphNodeView.Granularity granularity) {
        return traverse(tenantId, projectId, tableId, depth, granularity, false);
    }

    private LineageGraph traverse(long tenantId, long projectId, String tableId,
                                   int depth, GraphNodeView.Granularity granularity, boolean upstream) {
        int d = clampDepth(depth);
        String relPattern = granularity == GraphNodeView.Granularity.COLUMN
                ? "FLOWS_TO|DERIVES_FROM" : "FLOWS_TO";
        String dir = upstream ? "<-" : "-";
        String arrow = upstream ? "-" : "->";

        String cypher = String.format("""
                MATCH path = (start {id:$tableId})%s[:%s*1..%d]%s(end)
                WHERE start.tenantId=$tenantId AND start.projectId=$projectId
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
                       NULL AS parentId, {} AS attrs
                LIMIT $limit""",
                dir, relPattern, d, arrow,
                granularity == GraphNodeView.Granularity.COLUMN ? "COLUMN" : "TABLE");

        List<Map<String, Object>> rows = execute(cypher, params(tenantId, projectId,
                "tableId", tableId, "limit", MAX_NODES));

        List<GraphNodeView> nodes = rows.stream().map(LineageQueryService::mapNode).toList();
        boolean truncated = nodes.size() >= MAX_NODES;
        if (truncated) logTruncation(tableId, d, nodes.size());

        return new LineageGraph(nodes, List.of(),
                granularity, d, truncated, truncated ? nodes.size() : null);
    }

    /** 列级上游。T025 */
    public LineageGraph columnUpstream(long tenantId, long projectId, String columnId, int depth) {
        return columnTraverse(tenantId, projectId, columnId, depth, true);
    }

    /** 列级下游。T025 */
    public LineageGraph columnDownstream(long tenantId, long projectId, String columnId, int depth) {
        return columnTraverse(tenantId, projectId, columnId, depth, false);
    }

    private LineageGraph columnTraverse(long tenantId, long projectId, String columnId,
                                         int depth, boolean upstream) {
        int d = clampDepth(depth);
        String dir = upstream ? "<-" : "-";
        String arrow = upstream ? "-" : "->";

        String cypher = String.format("""
                MATCH path = (start:Column {id:$columnId})%s[:DERIVES_FROM*1..%d]%s(end:Column)
                WHERE start.tenantId=$tenantId AND start.projectId=$projectId
                RETURN DISTINCT end.id AS id, 'COLUMN' AS type, end.name AS name,
                       NULL AS layer, 'COLUMN' AS granularity, NULL AS parentId,
                       {dataType: end.dataType, ordinal: end.ordinal} AS attrs
                LIMIT $limit""",
                dir, d, arrow);

        List<Map<String, Object>> rows = execute(cypher, params(tenantId, projectId,
                "columnId", columnId, "limit", MAX_NODES));

        List<GraphNodeView> nodes = rows.stream().map(LineageQueryService::mapNode).toList();
        boolean truncated = nodes.size() >= MAX_NODES;
        if (truncated) logTruncation(columnId, d, nodes.size());

        return new LineageGraph(nodes, List.of(),
                GraphNodeView.Granularity.COLUMN, d, truncated, truncated ? nodes.size() : null);
    }

    /**
     * 影响面：全下游可达（表+列）。T026
     * {@code [:FLOWS_TO|DERIVES_FROM*]} 闭包。
     */
    public ImpactResult impact(long tenantId, long projectId, String nodeId,
                                int depth, int offset, int limit) {
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
                    List.of(), List.of(), 0, false, null);
        }

        // 全下游闭包
        String cypher = String.format("""
                MATCH (start {id:$nodeId})-[:FLOWS_TO|DERIVES_FROM*1..%d]->(end)
                WHERE start.tenantId=$tenantId AND start.projectId=$projectId
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
                       NULL AS parentId, {} AS attrs
                SKIP $offset LIMIT $limit""", d);

        List<Map<String, Object>> rows = execute(cypher, params(tenantId, projectId,
                "nodeId", nodeId, "offset", offset, "limit", l));
        List<GraphNodeView> downstream = rows.stream().map(LineageQueryService::mapNode).toList();
        boolean truncated = downstream.size() >= l;
        if (truncated) logTruncation(nodeId, d, downstream.size());

        return new ImpactResult(root, downstream, List.of(),
                downstream.size(), truncated, truncated ? downstream.size() : null);
    }

    // ═════════════════════════════════════════════════════════════
    // US3: 指标血缘 + 运行态
    // ═════════════════════════════════════════════════════════════

    /** 指标血缘：COMPUTED_FROM 的表/列。T032。按 (metricType, metricId) 查（不依赖 metricKey 合成键）。 */
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

    /** 今日同步行数聚合。T033 */
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
