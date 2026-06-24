package com.dataweave.master.domain;

import org.springframework.data.repository.CrudRepository;
import java.util.List;

public interface WorkflowNodeRepository extends CrudRepository<WorkflowNode, Long> {
    List<WorkflowNode> findByWorkflowId(Long workflowId);

    List<WorkflowNode> findByWorkflowIdAndDeleted(Long workflowId, Integer deleted);

    /** 查所有关联指定任务的工作流节点（含已删除），用于删除任务前校验。 */
    List<WorkflowNode> findByTaskIdAndDeleted(Long taskId, Integer deleted);
}
