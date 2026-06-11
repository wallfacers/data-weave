package com.dataweave.master.domain;

import org.springframework.data.repository.CrudRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaskInstanceRepository extends CrudRepository<TaskInstance, UUID> {
    List<TaskInstance> findByState(String state);
    List<TaskInstance> findByTaskId(Long taskId);
    Optional<TaskInstance> findFirstByStateOrderByIdDesc(String state);
    List<TaskInstance> findByRunMode(String runMode);
    List<TaskInstance> findByWorkflowInstanceId(UUID workflowInstanceId);
}
