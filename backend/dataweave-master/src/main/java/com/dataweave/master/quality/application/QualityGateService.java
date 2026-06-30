package com.dataweave.master.quality.application;

import com.dataweave.master.application.InstanceStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * BLOCK 阻断下游 DAG（research D3 / 接缝 C —— 红线：零新增状态机状态）。
 *
 * <p>断言 FAIL + action=BLOCK 时：
 * <ol>
 *   <li>从绑定 task 的 workflow_node 出发，遍历 {@code workflow_edge} 取传递下游闭包。</li>
 *   <li>对每个下游 TASK 节点实例（{@code state=WAITING}），CAS
 *       {@code InstanceStateMachine#casTaskState(id, WAITING, SKIPPED)} + 写
 *       {@code failureReason=QUALITY_BLOCKED:rule=X result=Y}。</li>
 *   <li>乐观 CAS（WHERE state='WAITING'）：下游已被并发 claim 时影响 0 行让步（死锁防御不变量②）。</li>
 * </ol>
 *
 * <p>WARN 动作不触碰下游状态机（{@link QualityCheckRunner} 只记 result + 发信号）。
 * 下游传递闭包与冻结传递下游 {@code workflow_node_freeze} 同构（共享 {@code workflow_node} + {@code workflow_edge}）。
 */
@Service
public class QualityGateService {

    private static final Logger log = LoggerFactory.getLogger(QualityGateService.class);

    private final InstanceStateMachine stateMachine;
    private final JdbcTemplate jdbc;

    public QualityGateService(InstanceStateMachine stateMachine, JdbcTemplate jdbc) {
        this.stateMachine = stateMachine;
        this.jdbc = jdbc;
    }

    /**
     * @param taskInstanceId  出质量问题的上游任务实例
     * @param ruleId          断言定义 id（写 failureReason 追溯）
     * @param resultId        断言结果 id
     * @param boundTaskId     绑定的任务定义 id
     * @return 阻断成功的下游实例数
     */
    public int block(UUID taskInstanceId, Long ruleId, Long resultId, Long boundTaskId) {
        // 1. 找到绑定 task 的 workflow_node 所在工作流实例
        List<Long> nodeIds = jdbc.query(
                "SELECT wn.id FROM workflow_node wn"
                        + " JOIN task_instance ti ON ti.workflow_instance_id IS NOT NULL"
                        + " WHERE ti.id = ? AND wn.task_def_id = ? AND wn.deleted = 0",
                (rs, rowNum) -> rs.getLong(1),
                taskInstanceId, boundTaskId);
        if (nodeIds.isEmpty()) {
            log.debug("[QualityGate] 任务实例 {} 无绑定 workflow_node（单跑任务无下游），跳过阻断", taskInstanceId);
            return 0;
        }

        Set<Long> downstreamNodeIds = downstreamClosure(nodeIds.get(0));

        if (downstreamNodeIds.isEmpty()) {
            log.debug("[QualityGate] taskInstanceId={} 无下游节点，跳过阻断", taskInstanceId);
            return 0;
        }

        // 2. 找到这些下游 TASK 节点在当前工作流实例中的 WAITING 实例
        UUID workflowInstanceId = jdbc.queryForObject(
                "SELECT workflow_instance_id FROM task_instance WHERE id = ?",
                UUID.class, taskInstanceId);

        List<UUID> downstreamInstanceIds = jdbc.query(
                "SELECT ti.id FROM task_instance ti"
                        + " JOIN workflow_node wn ON wn.id = ti.node_id AND wn.deleted = 0"
                        + " WHERE ti.workflow_instance_id = ? AND ti.state = 'WAITING' AND ti.deleted = 0"
                        + " AND wn.id IN (" + downstreamNodeIds.stream().map(String::valueOf)
                        .collect(Collectors.joining(",")) + ")",
                (rs, rowNum) -> (UUID) rs.getObject(1),
                workflowInstanceId);

        // 3. 乐观 CAS 每个下游 WAITING→SKIPPED
        String reason = "QUALITY_BLOCKED:rule=" + ruleId + " result=" + resultId;
        int blocked = 0;
        for (UUID downstreamId : downstreamInstanceIds) {
            boolean ok = stateMachine.casTaskState(downstreamId, "WAITING", "SKIPPED");
            if (ok) {
                // failure_reason 在 casTaskState 不支持额外字段；另写
                jdbc.update("UPDATE task_instance SET failure_reason = ? WHERE id = ?", reason, downstreamId);
                blocked++;
                log.info("[QualityGate] BLOCK 阻断下游 {} reason={}", downstreamId, reason);
            }
        }
        return blocked;
    }

    /** BFS 取下游传递闭包（含弱依赖边，与工作流引擎冻结传递一致）。 */
    private Set<Long> downstreamClosure(Long startNodeId) {
        Set<Long> visited = new HashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(startNodeId);
        visited.add(startNodeId); // 排除自身

        while (!queue.isEmpty()) {
            Long current = queue.poll();
            List<Long> children = jdbc.query(
                    "SELECT we.to_node_id FROM workflow_edge we WHERE we.from_node_id = ? AND we.deleted = 0",
                    (rs, rowNum) -> rs.getLong(1), current);
            for (Long child : children) {
                if (visited.add(child)) {
                    queue.add(child);
                }
            }
        }
        visited.remove(startNodeId); // 只返回下游
        return visited;
    }
}
