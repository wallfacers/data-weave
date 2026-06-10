package com.dataweave.master.domain;

import org.springframework.data.repository.CrudRepository;
import java.util.List;

public interface WorkflowDependencyRepository extends CrudRepository<WorkflowDependency, Long> {
    List<WorkflowDependency> findByWorkflowId(Long workflowId);
    List<WorkflowDependency> findByDependWorkflowId(Long dependWorkflowId);
}
