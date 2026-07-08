package com.dataweave.master.application.authoring;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.dataweave.master.application.LineageQueryService;
import com.dataweave.master.application.lineage.grounding.CatalogGroundingService;
import com.dataweave.master.application.lineage.script.ScriptLineageService;
import com.dataweave.master.domain.TaskDefRepository;
import com.dataweave.master.domain.WorkflowEdge;
import com.dataweave.master.domain.WorkflowEdgeRepository;
import com.dataweave.master.domain.WorkflowNode;
import com.dataweave.master.domain.WorkflowNodeRepository;
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
    private final WorkflowEdgeRepository workflowEdges;
    private final WorkflowNodeRepository workflowNodes;
    private final TaskDefRepository taskDefs;

    public AuthoringContextService(LineageQueryService lineageQuery,
                                   ScriptLineageService scriptLineage,
                                   CatalogGroundingService grounding,
                                   WorkflowEdgeRepository workflowEdges,
                                   WorkflowNodeRepository workflowNodes,
                                   TaskDefRepository taskDefs) {
        this.lineageQuery = lineageQuery;
        this.scriptLineage = scriptLineage;
        this.grounding = grounding;
        this.workflowEdges = workflowEdges;
        this.workflowNodes = workflowNodes;
        this.taskDefs = taskDefs;
    }

    /**
     * 装配某任务的创作上下文包（US1 / T010 落地真逻辑）。
     * 骨架阶段返回空壳（depthUsed 回显请求深度），保证编译与三面接线可先行。
     */
    public AuthoringContext context(long tenantId, long projectId, String taskRef, int depth) {
        // TODO(T010): reads/writes 上下游装配 + 列血缘 + 接地态 + 深度自决/广度截断。
        return new AuthoringContext(taskRef, List.of(), List.of(), List.of(),
                Map.of(), depth, List.of(), List.of());
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
