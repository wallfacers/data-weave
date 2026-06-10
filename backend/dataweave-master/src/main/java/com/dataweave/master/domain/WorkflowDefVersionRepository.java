package com.dataweave.master.domain;

import org.springframework.data.repository.CrudRepository;
import java.util.List;
import java.util.Optional;

public interface WorkflowDefVersionRepository extends CrudRepository<WorkflowDefVersion, Long> {
    List<WorkflowDefVersion> findByWorkflowIdOrderByVersionNoDesc(Long workflowId);
    Optional<WorkflowDefVersion> findByWorkflowIdAndVersionNo(Long workflowId, Integer versionNo);
}
