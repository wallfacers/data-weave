package com.dataweave.master.application.authoring;

import java.util.List;
import java.util.Map;

import com.dataweave.master.application.LineageQueryService;
import com.dataweave.master.application.lineage.grounding.CatalogGroundingService;
import com.dataweave.master.application.lineage.script.ScriptLineageService;
import com.dataweave.master.domain.TaskDefRepository;
import com.dataweave.master.domain.WorkflowEdgeRepository;
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
}
