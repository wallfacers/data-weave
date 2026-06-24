package com.dataweave.master.domain;

import org.springframework.data.repository.CrudRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaskInstanceRepository extends CrudRepository<TaskInstance, UUID> {
    List<TaskInstance> findByState(String state);
    List<TaskInstance> findByTaskId(Long taskId);
    Optional<TaskInstance> findFirstByStateOrderByIdDesc(String state);
    /** 某任务定义按 run_mode 过滤后的最近一个实例（id=UUIDv7 时间序，降序取最新）——供前端重开续接运行态。 */
    Optional<TaskInstance> findFirstByTaskIdAndRunModeOrderByIdDesc(Long taskId, String runMode);
    List<TaskInstance> findByRunMode(String runMode);
    List<TaskInstance> findByWorkflowInstanceId(UUID workflowInstanceId);
}
