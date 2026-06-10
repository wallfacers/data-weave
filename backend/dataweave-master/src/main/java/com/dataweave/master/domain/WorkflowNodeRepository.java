package com.dataweave.master.domain;

import org.springframework.data.repository.CrudRepository;
import java.util.List;

public interface WorkflowNodeRepository extends CrudRepository<WorkflowNode, Long> {
    List<WorkflowNode> findByWorkflowId(Long workflowId);
}
