package com.dataweave.master.domain;

import org.springframework.data.repository.CrudRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowInstanceRepository extends CrudRepository<WorkflowInstance, UUID> {
    List<WorkflowInstance> findByState(String state);
    List<WorkflowInstance> findByWorkflowId(Long workflowId);
    /** 某工作流定义的最近一个实例（id=UUIDv7 时间序，降序取最新）——供前端重开续接运行态。 */
    Optional<WorkflowInstance> findFirstByWorkflowIdOrderByIdDesc(Long workflowId);
}
