package com.dataweave.master.application;

import com.dataweave.master.domain.GraphCycles;
import com.dataweave.master.domain.WorkflowDependency;
import com.dataweave.master.domain.WorkflowDependencyRepository;
import com.dataweave.master.domain.WorkflowEdge;
import com.dataweave.master.domain.WorkflowEdgeRepository;
import com.dataweave.master.i18n.BizException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 发布期图校验（design D3 死锁防御之任务侧）：
 * <ul>
 *   <li>工作流发布：节点-边构成的 DAG 必须无环（有环拒绝发布）；</li>
 *   <li>创建跨流依赖：加入新依赖后全局 workflow_dependency 图必须无环。</li>
 * </ul>
 * 校验失败抛 {@link BizException}（code 以 {@code workflow.graph.*} 前缀），由发布/依赖创建入口转为面向用户的拒绝。
 */
@Service
public class WorkflowGraphValidator {

    private final WorkflowEdgeRepository edgeRepository;
    private final WorkflowDependencyRepository dependencyRepository;

    public WorkflowGraphValidator(WorkflowEdgeRepository edgeRepository,
                                  WorkflowDependencyRepository dependencyRepository) {
        this.edgeRepository = edgeRepository;
        this.dependencyRepository = dependencyRepository;
    }

    /** 工作流内 DAG 拓扑环检测。有环抛异常并提示环路节点。 */
    public void validateWorkflowDagAcyclic(Long workflowId) {
        List<WorkflowEdge> edges = edgeRepository.findByWorkflowIdAndDeleted(workflowId, 0);
        Map<Long, List<Long>> adjacency = new HashMap<>();
        for (WorkflowEdge e : edges) {
            adjacency.computeIfAbsent(e.getFromNodeId(), k -> new ArrayList<>()).add(e.getToNodeId());
        }
        Optional<List<Long>> cycle = GraphCycles.findCycle(adjacency);
        if (cycle.isPresent()) {
            throw new BizException("workflow.graph.cycle", pathString(cycle.get())).withHttpStatus(400);
        }
    }

    /**
     * 跨流依赖全局环检测：在现有 workflow_dependency 图上加入 (workflowId 依赖 dependWorkflowId)
     * 这条边后是否成环。{@code workflowId} 为下游（依赖方），{@code dependWorkflowId} 为上游（被依赖）。
     */
    public void validateDependencyAcyclic(Long workflowId, Long dependWorkflowId) {
        if (Objects.equals(workflowId, dependWorkflowId)) {
            throw new BizException("workflow.graph.self_dependency").withHttpStatus(400);
        }
        Map<Long, List<Long>> adjacency = new HashMap<>();
        for (WorkflowDependency d : dependencyRepository.findAll()) {
            if (d.getEnabled() != null && d.getEnabled() == 0) {
                continue;
            }
            adjacency.computeIfAbsent(d.getWorkflowId(), k -> new ArrayList<>())
                    .add(d.getDependWorkflowId());
        }
        // 加入待创建的依赖边（下游 → 上游）
        adjacency.computeIfAbsent(workflowId, k -> new ArrayList<>()).add(dependWorkflowId);

        Optional<List<Long>> cycle = GraphCycles.findCycle(adjacency);
        if (cycle.isPresent()) {
            throw new BizException("workflow.graph.dependency_cycle", pathString(cycle.get())).withHttpStatus(400);
        }
    }

    private String pathString(List<Long> path) {
        return path.stream().map(String::valueOf).collect(Collectors.joining(" → "));
    }
}
