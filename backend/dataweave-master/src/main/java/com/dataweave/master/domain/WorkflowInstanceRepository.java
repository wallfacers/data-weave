package com.dataweave.master.domain;

import org.springframework.data.repository.CrudRepository;
import java.util.List;

public interface WorkflowInstanceRepository extends CrudRepository<WorkflowInstance, Long> {
    List<WorkflowInstance> findByState(String state);
    List<WorkflowInstance> findByWorkflowId(Long workflowId);
}
