package com.dataweave.master.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 工作流发布快照结构（序列化进 {@code workflow_def_version.dag_snapshot_json}）。
 *
 * <p><strong>规定性快照（workflow-version-binding）</strong>：发布时冻结整张 DAG——拓扑（nodes/edges）
 * 与各 TASK 节点的 {@code taskVersionNo}。运行期触发以此为<strong>唯一真相源</strong>物化拓扑与版本，
 * 不再读 live {@code workflow_node} / {@code task_def.current_version_no}。
 *
 * <p>字段名与历史私有记录保持一致，旧快照 JSON 可继续反序列化。{@link JsonIgnoreProperties} 容忍未来
 * 新增字段（如 edge.strength，由 workflow-dependency-modes 叠加），跨 change 反序列化不互相破坏。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowDagSnapshot(List<Node> nodes, List<Edge> edges) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Node(String nodeKey, String nodeType, Long taskId, Integer taskVersionNo,
                       String name, Integer posX, Integer posY) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Edge(String fromNodeKey, String toNodeKey, String strength) {}
}
