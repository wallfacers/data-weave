package com.dataweave.master.domain;

import org.springframework.data.repository.CrudRepository;
import java.util.List;

public interface WorkflowEdgeRepository extends CrudRepository<WorkflowEdge, Long> {
    List<WorkflowEdge> findByWorkflowId(Long workflowId);

    List<WorkflowEdge> findByWorkflowIdAndDeleted(Long workflowId, Integer deleted);
}
