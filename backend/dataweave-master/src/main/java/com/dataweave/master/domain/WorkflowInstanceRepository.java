package com.dataweave.master.domain;

import org.springframework.data.repository.CrudRepository;
import java.util.List;
import java.util.UUID;

public interface WorkflowInstanceRepository extends CrudRepository<WorkflowInstance, UUID> {
    List<WorkflowInstance> findByState(String state);
    List<WorkflowInstance> findByWorkflowId(Long workflowId);
}
