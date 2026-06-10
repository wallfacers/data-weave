package com.dataweave.master.domain;

import org.springframework.data.repository.CrudRepository;
import java.util.List;
import java.util.Optional;

public interface TaskInstanceRepository extends CrudRepository<TaskInstance, Long> {
    List<TaskInstance> findByState(String state);
    Optional<TaskInstance> findFirstByStateOrderByIdDesc(String state);
    List<TaskInstance> findByRunMode(String runMode);
    List<TaskInstance> findByWorkflowInstanceId(Long workflowInstanceId);
}
