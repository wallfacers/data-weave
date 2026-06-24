package com.dataweave.master.application;

import com.dataweave.master.domain.DataTable;
import com.dataweave.master.domain.DataTableRepository;
import com.dataweave.master.domain.TaskTableIo;
import com.dataweave.master.domain.TaskTableIoRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 表级/任务级血缘图服务（table-lineage）：表为节点、任务为边的二部图。
 *
 * <p>设计态边（{@code task_table_io}）在建任务时即写入，无需任务先运行。对外暴露的「数据流」
 * 视图由「任务的 READ 表 × WRITE 表」推导为 表→表 的 {@link FlowEdge}，供态势驾驶舱活血缘图渲染。
 * 独立于指标域 {@code metric_lineage}，二者并存。
 */
@Service
public class LineageGraphService {

    private final DataTableRepository dataTableRepository;
    private final TaskTableIoRepository taskTableIoRepository;
    private final JdbcTemplate jdbcTemplate;

    public LineageGraphService(DataTableRepository dataTableRepository,
                               TaskTableIoRepository taskTableIoRepository,
                               JdbcTemplate jdbcTemplate) {
        this.dataTableRepository = dataTableRepository;
        this.taskTableIoRepository = taskTableIoRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    // ─── 对外数据结构 ────────────────────────────────────────

    public static final String READ = "READ";
    public static final String WRITE = "WRITE";

    /** 一条设计态血缘边的输入（建任务时由 AGENT 声明 / SQL_PARSED / FORM 产出）。 */
    public record EdgeInput(Long datasourceId, String qualifiedName, String layer,
                            String direction, String source, String confidence) {}

    /** 血缘图节点（表）。 */
    public record GraphNode(Long id, Long datasourceId, String qualifiedName, String layer) {}

    /** 表→表的数据流边（经某任务），confidence 取读写两端较低可信度。 */
    public record FlowEdge(Long fromTableId, Long toTableId, Long taskDefId, String confidence) {}

    /** 一张血缘（子）图。 */
    public record LineageGraph(List<GraphNode> nodes, List<FlowEdge> edges) {}

    // ─── 写：建任务即建血缘 ──────────────────────────────────

    /**
     * 记录任务的设计态血缘：清除旧边（替换语义）→ upsert 表节点 → 写新边。
     * 应在建任务/上线事务内调用。空 qualifiedName 的边跳过。
     */
    @Transactional
    public void recordDesignTimeIo(long tenantId, long projectId, long taskDefId,
                                   Integer versionNo, List<EdgeInput> edges) {
        taskTableIoRepository.deleteByTaskDefId(taskDefId);
        if (edges == null || edges.isEmpty()) return;
        LocalDateTime now = LocalDateTime.now();
        for (EdgeInput e : edges) {
            if (e.qualifiedName() == null || e.qualifiedName().isBlank()) continue;
            DataTable node = upsertTable(tenantId, projectId, e.datasourceId(),
                    e.qualifiedName().trim(), e.layer(), now);
            TaskTableIo io = new TaskTableIo();
            io.setTenantId(tenantId);
            io.setProjectId(projectId);
            io.setTaskDefId(taskDefId);
            io.setTaskVersionNo(versionNo);
            io.setTableId(node.getId());
            io.setDirection(WRITE.equalsIgnoreCase(e.direction()) ? WRITE : READ);
            io.setSource(e.source() != null ? e.source() : "FORM");
            io.setConfidence(e.confidence() != null ? e.confidence() : "UNVERIFIED");
            io.setCreatedAt(now);
            io.setUpdatedAt(now);
            io.setDeleted(0);
            io.setVersion(0);
            taskTableIoRepository.save(io);
        }
    }

    /** find-or-create 表节点；datasource_id 缺省以 0（未知源）保证唯一约束可比。 */
    private DataTable upsertTable(long tenantId, long projectId, Long dsId, String qn,
                                  String layerOverride, LocalDateTime now) {
        long ds = dsId == null ? 0L : dsId;
        return dataTableRepository.findFirstByDatasourceIdAndQualifiedName(ds, qn)
                .orElseGet(() -> {
                    DataTable t = new DataTable();
                    t.setTenantId(tenantId);
                    t.setProjectId(projectId);
                    t.setDatasourceId(ds);
                    t.setQualifiedName(qn);
                    t.setLayer(layerOverride != null && !layerOverride.isBlank()
                            ? layerOverride.toUpperCase() : inferLayer(qn));
                    t.setCreatedAt(now);
                    t.setUpdatedAt(now);
                    t.setDeleted(0);
                    t.setVersion(0);
                    return dataTableRepository.save(t);
                });
    }

    /** 命名前缀推导分层（ods_/dwd_/dws_/ads_，大小写不敏感）；不匹配返回 null。 */
    public static String inferLayer(String qualifiedName) {
        if (qualifiedName == null) return null;
        String t = qualifiedName.toLowerCase();
        int dot = t.lastIndexOf('.');
        if (dot >= 0) t = t.substring(dot + 1);
        if (t.startsWith("ods_") || t.equals("ods")) return "ODS";
        if (t.startsWith("dwd_")) return "DWD";
        if (t.startsWith("dws_")) return "DWS";
        if (t.startsWith("ads_")) return "ADS";
        return null;
    }

    // ─── 读：全局图 / 邻域 / 上下游 ──────────────────────────

    /** 全局血缘图（某租户+项目下所有表节点 + 表→表流边）。 */
    public LineageGraph globalGraph(long tenantId, long projectId) {
        List<GraphNode> nodes = loadNodes(tenantId, projectId);
        List<FlowEdge> edges = loadFlowEdges(tenantId, projectId);
        return new LineageGraph(nodes, edges);
    }

    /** 以某表为中心、depth 跳邻域的诱导子图（双向 BFS）。 */
    public LineageGraph neighborhood(long tenantId, long projectId, long centerTableId, int depth) {
        List<GraphNode> allNodes = loadNodes(tenantId, projectId);
        List<FlowEdge> allEdges = loadFlowEdges(tenantId, projectId);
        Set<Long> keep = bfs(allEdges, centerTableId, depth, true, true);
        keep.add(centerTableId);
        List<GraphNode> nodes = allNodes.stream().filter(n -> keep.contains(n.id())).toList();
        List<FlowEdge> edges = allEdges.stream()
                .filter(e -> keep.contains(e.fromTableId()) && keep.contains(e.toTableId())).toList();
        return new LineageGraph(nodes, edges);
    }

    /** 某表的上游表（谁产出了它，沿流边反向可达）。 */
    public List<GraphNode> upstream(long tenantId, long projectId, long tableId) {
        return reachableNodes(tenantId, projectId, tableId, false, true);
    }

    /** 某表的下游表（谁消费了它，影响分析，沿流边正向可达）。 */
    public List<GraphNode> downstream(long tenantId, long projectId, long tableId) {
        return reachableNodes(tenantId, projectId, tableId, true, false);
    }

    private List<GraphNode> reachableNodes(long tenantId, long projectId, long tableId,
                                           boolean forward, boolean backward) {
        List<GraphNode> allNodes = loadNodes(tenantId, projectId);
        List<FlowEdge> allEdges = loadFlowEdges(tenantId, projectId);
        Set<Long> reach = bfs(allEdges, tableId, Integer.MAX_VALUE, forward, backward);
        reach.remove(tableId);
        return allNodes.stream().filter(n -> reach.contains(n.id())).toList();
    }

    /** 在流边集合上从 start 做有界 BFS，返回可达节点 id 集合（含按方向）。 */
    private Set<Long> bfs(List<FlowEdge> edges, long start, int maxDepth,
                          boolean forward, boolean backward) {
        Set<Long> visited = new HashSet<>();
        Deque<long[]> queue = new ArrayDeque<>(); // [tableId, depth]
        queue.add(new long[]{start, 0});
        visited.add(start);
        while (!queue.isEmpty()) {
            long[] cur = queue.poll();
            long node = cur[0];
            int d = (int) cur[1];
            if (d >= maxDepth) continue;
            for (FlowEdge e : edges) {
                Long next = null;
                if (forward && e.fromTableId() != null && e.fromTableId() == node) next = e.toTableId();
                else if (backward && e.toTableId() != null && e.toTableId() == node) next = e.fromTableId();
                if (next != null && !visited.contains(next)) {
                    visited.add(next);
                    queue.add(new long[]{next, d + 1});
                }
            }
        }
        return visited;
    }

    private List<GraphNode> loadNodes(long tenantId, long projectId) {
        return jdbcTemplate.query(
                "SELECT id, datasource_id, qualified_name, layer FROM data_table " +
                        "WHERE deleted = 0 AND tenant_id = ? AND project_id = ?",
                (rs, n) -> new GraphNode(
                        rs.getLong("id"),
                        rs.getLong("datasource_id"),
                        rs.getString("qualified_name"),
                        rs.getString("layer")),
                tenantId, projectId);
    }

    /**
     * 今日同步行数（运行态聚合）：最近业务日期下所有 WRITE 边的 row_count 之和。
     * row_count 为 NULL（未采集）的行不计入——口径为「已采集任务」，避免误导。
     * 无运行态数据时返回 null（前端据此显示「估算中」而非编造 0）。
     */
    public Long syncedRowsLatestDay(long tenantId, long projectId) {
        String sql =
                "SELECT SUM(row_count) FROM task_run_table_io " +
                "WHERE direction = 'WRITE' AND deleted = 0 AND row_count IS NOT NULL " +
                "  AND tenant_id = ? AND project_id = ? " +
                "  AND (biz_date IS NULL OR biz_date = (" +
                "      SELECT MAX(biz_date) FROM task_run_table_io " +
                "      WHERE direction = 'WRITE' AND deleted = 0 AND tenant_id = ? AND project_id = ?))";
        return jdbcTemplate.queryForObject(sql, Long.class, tenantId, projectId, tenantId, projectId);
    }

    private List<FlowEdge> loadFlowEdges(long tenantId, long projectId) {
        // 表→表流边：同一任务的 READ 表 × WRITE 表；confidence 取两端较低。
        String sql =
                "SELECT r.table_id AS from_id, w.table_id AS to_id, r.task_def_id AS task_id, " +
                "  CASE WHEN r.confidence = 'CONFLICT' OR w.confidence = 'CONFLICT' THEN 'CONFLICT' " +
                "       WHEN r.confidence = 'UNVERIFIED' OR w.confidence = 'UNVERIFIED' THEN 'UNVERIFIED' " +
                "       ELSE 'CONFIRMED' END AS conf " +
                "FROM task_table_io r " +
                "JOIN task_table_io w ON r.task_def_id = w.task_def_id " +
                "WHERE r.direction = 'READ' AND w.direction = 'WRITE' " +
                "  AND r.deleted = 0 AND w.deleted = 0 " +
                "  AND r.tenant_id = ? AND r.project_id = ?";
        return jdbcTemplate.query(sql,
                (rs, n) -> new FlowEdge(
                        rs.getLong("from_id"),
                        rs.getLong("to_id"),
                        rs.getLong("task_id"),
                        rs.getString("conf")),
                tenantId, projectId);
    }
}
