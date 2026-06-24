package com.dataweave.master.application;

import com.dataweave.master.domain.InstanceStates;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.TaskInstanceRepository;
import com.dataweave.master.domain.WorkflowEdge;
import com.dataweave.master.domain.WorkflowEdgeRepository;
import com.dataweave.master.domain.WorkflowNode;
import com.dataweave.master.domain.WorkflowNodeFreeze;
import com.dataweave.master.domain.WorkflowNodeFreezeRepository;
import com.dataweave.master.domain.WorkflowNodeRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 节点级 DAG 冻结服务（ops-center-publish-boundary）。
 *
 * <p>冻结状态存运维侧 overlay（{@link WorkflowNodeFreeze}），不写入发布快照——快照「发布即不可变」是运行真相源。
 * 作用域两种：
 * <ul>
 *   <li><b>定义级</b>（{@code instanceId==null}）：影响此后每个 cron 物化实例，由
 *       {@link WorkflowTriggerService} 在物化期叠加并将冻结节点及其传递下游标 SKIPPED。</li>
 *   <li><b>实例级</b>（{@code instanceId!=null}）：仅对该已生成实例生效，本服务在冻结时即时把该实例中
 *       「冻结节点 + 传递下游闭包」里仍非终态（WAITING/NOT_RUN）的节点 CAS → SKIPPED，并重算工作流聚合态。</li>
 * </ul>
 * 级联沿后继边 BFS，<b>含弱依赖边</b>——冻结优先于依赖强弱，下游一律不跑。
 */
@Service
public class NodeFreezeService {

    private final WorkflowNodeFreezeRepository freezeRepository;
    private final WorkflowNodeRepository nodeRepository;
    private final WorkflowEdgeRepository edgeRepository;
    private final TaskInstanceRepository taskInstanceRepository;
    private final InstanceStateMachine stateMachine;
    private final WorkflowStateService workflowStateService;

    public NodeFreezeService(WorkflowNodeFreezeRepository freezeRepository,
                             WorkflowNodeRepository nodeRepository,
                             WorkflowEdgeRepository edgeRepository,
                             TaskInstanceRepository taskInstanceRepository,
                             InstanceStateMachine stateMachine,
                             WorkflowStateService workflowStateService) {
        this.freezeRepository = freezeRepository;
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
        this.taskInstanceRepository = taskInstanceRepository;
        this.stateMachine = stateMachine;
        this.workflowStateService = workflowStateService;
    }

    /**
     * 冻结/解冻某工作流的某节点。
     *
     * @param instanceId 空=定义级；非空=该工作流实例级
     * @param frozen     true=冻结、false=解冻
     * @return 落库后的 overlay 记录
     */
    public WorkflowNodeFreeze setFrozen(Long workflowId, String nodeKey, UUID instanceId,
                                        boolean frozen, Long tenantId, Long projectId, Long actor) {
        WorkflowNodeFreeze row = instanceId == null
                ? freezeRepository.findDefinitionRow(workflowId, nodeKey)
                : freezeRepository.findInstanceRow(workflowId, nodeKey, instanceId);
        LocalDateTime now = LocalDateTime.now();
        if (row == null) {
            row = new WorkflowNodeFreeze();
            row.setTenantId(tenantId);
            row.setProjectId(projectId);
            row.setWorkflowId(workflowId);
            row.setNodeKey(nodeKey);
            row.setInstanceId(instanceId);
            row.setCreatedBy(actor);
            row.setCreatedAt(now);
            row.setDeleted(0);
            row.setVersion(0L);
        }
        row.setFrozen(frozen ? 1 : 0);
        row.setUpdatedBy(actor);
        row.setUpdatedAt(now);
        WorkflowNodeFreeze saved = freezeRepository.save(row);

        // 实例级冻结即时级联：把该实例「冻结节点 + 传递下游」仍非终态的节点标 SKIPPED，并重算聚合态。
        if (frozen && instanceId != null) {
            cascadeSkipInstance(workflowId, nodeKey, instanceId);
        }
        return saved;
    }

    /** 某工作流的全部未删冻结记录（定义级 + 各实例级），列表/管理用。 */
    public List<WorkflowNodeFreeze> list(Long workflowId) {
        return freezeRepository.findByWorkflowIdAndDeleted(workflowId, 0);
    }

    /** 实例级冻结的级联：seed 节点及其后继闭包（含弱依赖边）中仍 WAITING/NOT_RUN 的节点 CAS→SKIPPED。 */
    private void cascadeSkipInstance(Long workflowId, String seedKey, UUID instanceId) {
        List<WorkflowNode> liveNodes = nodeRepository.findByWorkflowIdAndDeleted(workflowId, 0);
        Map<String, Long> keyToId = new HashMap<>();
        Map<Long, String> idToKey = new HashMap<>();
        for (WorkflowNode n : liveNodes) {
            keyToId.put(n.getNodeKey(), n.getId());
            idToKey.put(n.getId(), n.getNodeKey());
        }
        Map<String, List<String>> forward = new HashMap<>();
        for (WorkflowEdge e : edgeRepository.findByWorkflowIdAndDeleted(workflowId, 0)) {
            String from = idToKey.get(e.getFromNodeId());
            String to = idToKey.get(e.getToNodeId());
            if (from != null && to != null) {
                forward.computeIfAbsent(from, k -> new java.util.ArrayList<>()).add(to);
            }
        }
        Set<String> closure = WorkflowTriggerService.downstreamClosure(Set.of(seedKey), forward);

        List<TaskInstance> nodes = taskInstanceRepository.findByWorkflowInstanceId(instanceId);
        for (TaskInstance ti : nodes) {
            if (ti == null || ti.getWorkflowNodeId() == null) {
                continue;
            }
            String key = idToKey.get(ti.getWorkflowNodeId());
            if (key == null || !closure.contains(key)) {
                continue;
            }
            String state = ti.getState();
            // 仅跳过尚未启动的节点；已 RUNNING/DISPATCHED 的不强行中断（那是「停止」语义，非冻结）。
            if (InstanceStates.WAITING.equals(state) || InstanceStates.NOT_RUN.equals(state)) {
                stateMachine.casTaskTerminal(ti.getId(), state, InstanceStates.SKIPPED, "FROZEN");
            }
        }
        workflowStateService.computeAndUpdate(instanceId);
    }
}
