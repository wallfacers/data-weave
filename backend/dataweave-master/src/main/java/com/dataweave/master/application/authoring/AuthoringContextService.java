package com.dataweave.master.application.authoring;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.dataweave.master.application.LineageQueryService;
import com.dataweave.master.application.lineage.DatasourceBoundCatalog;
import com.dataweave.master.application.lineage.TaskLineageResolver;
import com.dataweave.master.application.lineage.grounding.CatalogGroundingService;
import com.dataweave.master.application.lineage.grounding.TableExistence;
import com.dataweave.master.application.lineage.script.ScriptLineageService;
import com.dataweave.master.domain.TaskDef;
import com.dataweave.master.domain.TaskDefRepository;
import com.dataweave.master.domain.WorkflowEdge;
import com.dataweave.master.domain.WorkflowEdgeRepository;
import com.dataweave.master.domain.WorkflowNode;
import com.dataweave.master.domain.WorkflowNodeRepository;
import com.dataweave.master.domain.lineage.ColumnEdge;
import com.dataweave.master.domain.lineage.Direction;
import com.dataweave.master.domain.lineage.IoEdge;
import com.dataweave.master.domain.lineage.TableRef;
import com.dataweave.master.lineage.FlowEdgeView;
import com.dataweave.master.lineage.GraphNodeView;
import com.dataweave.master.lineage.LineageGraph;
import org.springframework.stereotype.Service;

/**
 * 058 创作上下文编排服务（US1-US3 的单一后端能力，CLI/MCP/REST 三面共用，SC-006）。
 *
 * <p>纯读 + 诊断、确定性零 LLM；编排复用既有能力，不新建血缘逻辑（宪法 V）：
 * <ul>
 *   <li>{@link LineageQueryService} —— 表/列上下游图查（upstream/downstream/neighborhood/columnUpstream）</li>
 *   <li>{@link ScriptLineageService} —— 草稿抽取（经 {@link DraftLineage} 归一，D3 不 fork）</li>
 *   <li>{@link CatalogGroundingService} —— 存在真伪裁决（接地态）</li>
 *   <li>{@link WorkflowEdgeRepository}/{@link WorkflowNodeRepository} —— 声明 DAG 依赖</li>
 *   <li>{@link TaskDefRepository} —— 复用候选来源</li>
 * </ul>
 *
 * <p>方法按分期落地：{@link #context} = US1(T010)；taskDependencies=US1(T011)；
 * reuseCandidates=US2；diagnose=US3。本骨架仅 context 留桩。
 */
@Service
public class AuthoringContextService {

    private final LineageQueryService lineageQuery;
    private final ScriptLineageService scriptLineage;
    private final CatalogGroundingService grounding;
    private final TaskLineageResolver taskLineageResolver;
    private final WorkflowEdgeRepository workflowEdges;
    private final WorkflowNodeRepository workflowNodes;
    private final TaskDefRepository taskDefs;

    public AuthoringContextService(LineageQueryService lineageQuery,
                                   ScriptLineageService scriptLineage,
                                   CatalogGroundingService grounding,
                                   TaskLineageResolver taskLineageResolver,
                                   WorkflowEdgeRepository workflowEdges,
                                   WorkflowNodeRepository workflowNodes,
                                   TaskDefRepository taskDefs) {
        this.lineageQuery = lineageQuery;
        this.scriptLineage = scriptLineage;
        this.grounding = grounding;
        this.taskLineageResolver = taskLineageResolver;
        this.workflowEdges = workflowEdges;
        this.workflowNodes = workflowNodes;
        this.taskDefs = taskDefs;
    }

    /**
     * 装配某任务的创作上下文包（US1 / T010 落地真逻辑）。
     * 骨架阶段返回空壳（depthUsed 回显请求深度），保证编译与三面接线可先行。
     */
    public AuthoringContext context(long tenantId, long projectId, String taskRef, int depth) {
        List<AuthoringContext.MissingNote> partial = new ArrayList<>();

        Long taskDefId = parseLong(taskRef);
        TaskDef def = taskDefId != null ? taskDefs.findById(taskDefId).orElse(null) : null;
        if (def == null || !scoped(def.getTenantId(), def.getProjectId(), tenantId, projectId)) {
            partial.add(new AuthoringContext.MissingNote("task", "未找到任务或非本项目：" + taskRef));
            return new AuthoringContext(taskRef, List.of(), List.of(), List.of(), Map.of(), depth, List.of(), partial);
        }

        // ① 读写表：复用只读共享核抽取（与 push 同一逻辑，D3 不 fork）
        TaskLineageResolver.ResolvedLineage resolved;
        try {
            resolved = taskLineageResolver.resolve(tenantId, projectId, taskDefId,
                    def.getType(), def.getContent(), def.getDatasourceId(), def.getTargetDatasourceId(),
                    List.of(), List.of());
        } catch (Exception e) {
            partial.add(new AuthoringContext.MissingNote("extractor", "血缘抽取失败：" + e));
            return new AuthoringContext(taskRef, List.of(), List.of(), List.of(), Map.of(), depth, List.of(), partial);
        }

        Long readDs = def.getDatasourceId();
        Long writeDs = def.getTargetDatasourceId() != null ? def.getTargetDatasourceId() : def.getDatasourceId();

        List<AuthoringContext.TableFact> reads = new ArrayList<>();
        List<AuthoringContext.TableFact> writes = new ArrayList<>();
        List<AuthoringContext.TruncationNote> truncated = new ArrayList<>();

        for (IoEdge e : resolved.ioEdges()) {
            TableRef ref = e.table();
            if (ref == null || ref.qualifiedName() == null || ref.qualifiedName().isBlank()) continue;
            boolean isRead = e.direction() == Direction.READS;
            Long dsId = isRead ? readDs : writeDs;
            String state = groundingState(tenantId, projectId, ref.qualifiedName(), dsId, partial);
            // ② 邻居：读表→上游表、写表→下游表（表级图；任务级依赖由 taskDependencies 覆盖，不重叠）
            List<AuthoringContext.NodeRef> neighbors =
                    tableNeighbors(tenantId, projectId, ref, depth, isRead, truncated, partial);
            AuthoringContext.TableFact fact = new AuthoringContext.TableFact(
                    ref.qualifiedName(),
                    ref.datasource() != null ? ref.datasource().dsKey() : null,
                    isRead ? "READS" : "WRITES", neighbors, state, nameOf(e.source()));
            (isRead ? reads : writes).add(fact);
        }

        // ③ 列级血缘：直接取抽取产物（确定性、无需图库）
        List<AuthoringContext.ColumnEdgeFact> columnLineage = new ArrayList<>();
        for (ColumnEdge c : resolved.columnEdges()) {
            columnLineage.add(new AuthoringContext.ColumnEdgeFact(
                    c.srcTable() != null ? c.srcTable().qualifiedName() : null, c.srcCol(),
                    c.dstTable() != null ? c.dstTable().qualifiedName() : null, c.dstCol()));
        }

        return new AuthoringContext(taskRef, reads, writes, columnLineage, Map.of(), depth, truncated, partial);
    }

    /** 三态存在性接地：PRESENT→接地；ABSENT→未接地；UNKNOWN/探测失败→推断（不虚构，SC-005）。 */
    private String groundingState(long tenantId, long projectId, String qualifiedName, Long dsId,
                                  List<AuthoringContext.MissingNote> partial) {
        try {
            DatasourceBoundCatalog catalog = taskLineageResolver.catalogFor(dsId);
            TableExistence ex = catalog.probeExistence(tenantId, projectId, qualifiedName);
            return switch (ex) {
                case PRESENT -> "PRESENT";
                case ABSENT -> "UNGROUNDED";
                case UNKNOWN -> "INFERRED";
            };
        } catch (Exception e) {
            partial.add(new AuthoringContext.MissingNote("grounding", "存在性探测降级：" + qualifiedName));
            return "INFERRED";
        }
    }

    /** 表级上/下游邻居（读表取上游、写表取下游），BFS 标注跳距，超广度阈值截断并留痕（FR-018）。 */
    private List<AuthoringContext.NodeRef> tableNeighbors(long tenantId, long projectId, TableRef ref,
                                                          int depth, boolean upstream,
                                                          List<AuthoringContext.TruncationNote> truncated,
                                                          List<AuthoringContext.MissingNote> partial) {
        String seedId = tableId(ref);
        if (seedId == null) return List.of();
        LineageGraph graph;
        try {
            graph = upstream
                    ? lineageQuery.upstream(tenantId, projectId, seedId, depth,
                            GraphNodeView.Granularity.TABLE, null, null, null, null)
                    : lineageQuery.downstream(tenantId, projectId, seedId, depth,
                            GraphNodeView.Granularity.TABLE, null, null, null, null);
        } catch (Exception e) {
            partial.add(new AuthoringContext.MissingNote("lineage-graph", "上下游查询降级：" + ref.qualifiedName()));
            return List.of();
        }
        Map<String, Integer> hops = bfsHops(seedId, graph.edges(), upstream);
        List<AuthoringContext.NodeRef> out = new ArrayList<>();
        int cap = LineageQueryService.clampLimit(0); // 平台默认广度上限
        for (GraphNodeView n : graph.nodes()) {
            if (n.id() == null || n.id().equals(seedId)) continue;
            if (n.type() != GraphNodeView.NodeType.TABLE) continue;
            if (out.size() >= cap) {
                truncated.add(new AuthoringContext.TruncationNote(ref.qualifiedName(),
                        "邻居数超广度阈值 " + cap + "，已截断"));
                break;
            }
            out.add(new AuthoringContext.NodeRef(n.id(), n.name(), "TABLE", hops.getOrDefault(n.id(), 1)));
        }
        return out;
    }

    /** 沿图边 BFS 标注各节点到种子的最短跳距（upstream 反向沿 to→from，downstream 正向 from→to）。 */
    private static Map<String, Integer> bfsHops(String seedId, List<FlowEdgeView> edges, boolean upstream) {
        Map<String, Integer> hops = new HashMap<>();
        hops.put(seedId, 0);
        java.util.Deque<String> queue = new java.util.ArrayDeque<>();
        queue.add(seedId);
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            int next = hops.get(cur) + 1;
            for (FlowEdgeView e : edges) {
                String nb = null;
                if (upstream && cur.equals(e.to())) nb = e.from();
                else if (!upstream && cur.equals(e.from())) nb = e.to();
                if (nb != null && !hops.containsKey(nb)) {
                    hops.put(nb, next);
                    queue.add(nb);
                }
            }
        }
        return hops;
    }

    /** Table 图节点 id = dsKey|norm(qualifiedName)（对齐 Neo4jLineageStore.tableKey，确保命中）。 */
    private static String tableId(TableRef ref) {
        if (ref == null || ref.datasource() == null || ref.qualifiedName() == null) return null;
        return ref.datasource().dsKey() + "|" + ref.qualifiedName().trim().toLowerCase();
    }

    private static Long parseLong(String s) {
        if (s == null) return null;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String nameOf(Enum<?> e) {
        return e != null ? e.name() : "UNKNOWN";
    }

    /**
     * 装配某任务的依赖视图（US1 / T011 / FR-006）：声明（{@code workflow_edge} DAG）与
     * 推导（血缘 {@code FLOWS_TO} 可达）双通道合并，带 origin 归属，供 agent 看清链路及背离。
     *
     * <p>纯读、确定性、无 LLM。声明边经 {@code workflow_node/edge} 仓储（租户+项目隔离硬校验）；
     * 推导边经 {@link LineageQueryService#upstreamTaskLevels}/{@link LineageQueryService#downstreamTaskLevels}
     * （neo4j 不可达自动降级空集，只保留声明边、不阻断——对标 FR-005）。
     */
    public TaskDependencyView taskDependencies(long tenantId, long projectId, long taskDefId) {
        List<DependencyEdge> declaredUp = new ArrayList<>();
        List<DependencyEdge> declaredDown = new ArrayList<>();
        String self = String.valueOf(taskDefId);

        for (WorkflowNode node : workflowNodes.findByTaskIdAndDeleted(taskDefId, 0)) {
            if (!scoped(node.getTenantId(), node.getProjectId(), tenantId, projectId)) continue;
            Long workflowId = node.getWorkflowId();
            Map<Long, Long> nodeToTask = new LinkedHashMap<>();
            for (WorkflowNode n : workflowNodes.findByWorkflowIdAndDeleted(workflowId, 0)) {
                if (n.getTaskId() != null) nodeToTask.put(n.getId(), n.getTaskId());
            }
            for (WorkflowEdge e : workflowEdges.findByWorkflowIdAndDeleted(workflowId, 0)) {
                if (node.getId().equals(e.getToNodeId())) {
                    Long up = nodeToTask.get(e.getFromNodeId());
                    if (up != null && !up.equals(taskDefId)) {
                        declaredUp.add(new DependencyEdge(String.valueOf(up), self, 1, DependencyEdge.DECLARED));
                    }
                } else if (node.getId().equals(e.getFromNodeId())) {
                    Long down = nodeToTask.get(e.getToNodeId());
                    if (down != null && !down.equals(taskDefId)) {
                        declaredDown.add(new DependencyEdge(self, String.valueOf(down), 1, DependencyEdge.DECLARED));
                    }
                }
            }
        }

        List<DependencyEdge> derivedUp = derivedEdges(
                lineageQuery.upstreamTaskLevels(tenantId, projectId, taskDefId), self, true);
        List<DependencyEdge> derivedDown = derivedEdges(
                lineageQuery.downstreamTaskLevels(tenantId, projectId, taskDefId), self, false);

        return TaskDependencyView.merge(self, declaredUp, derivedUp, declaredDown, derivedDown);
    }

    /** taskDefId→层级 映射转为推导依赖边（upstream=true 则 other 为 from，否则为 to）。 */
    private static List<DependencyEdge> derivedEdges(Map<Long, Integer> levels, String self, boolean upstream) {
        List<DependencyEdge> out = new ArrayList<>();
        for (Map.Entry<Long, Integer> en : levels.entrySet()) {
            String other = String.valueOf(en.getKey());
            int hop = en.getValue() != null ? en.getValue() : 1;
            out.add(upstream
                    ? new DependencyEdge(other, self, hop, DependencyEdge.DERIVED)
                    : new DependencyEdge(self, other, hop, DependencyEdge.DERIVED));
        }
        return out;
    }

    /** 租户+项目隔离硬校验（防跨租户/跨项目误读依赖）。 */
    private static boolean scoped(Long nodeTenant, Long nodeProject, long tenantId, long projectId) {
        return nodeTenant != null && nodeTenant == tenantId
                && nodeProject != null && nodeProject == projectId;
    }
}
