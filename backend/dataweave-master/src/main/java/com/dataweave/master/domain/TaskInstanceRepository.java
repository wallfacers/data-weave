package com.dataweave.master.domain;

import org.springframework.data.repository.CrudRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaskInstanceRepository extends CrudRepository<TaskInstance, UUID> {
    List<TaskInstance> findByState(String state);
    List<TaskInstance> findByTaskId(Long taskId);
    /** E 子特性：按租户过滤实例（MCP query_task_instances 隔离回补）。 */
    List<TaskInstance> findByTenantId(Long tenantId);
    /** E 子特性：按租户 + 状态过滤实例。 */
    List<TaskInstance> findByTenantIdAndState(Long tenantId, String state);
    Optional<TaskInstance> findFirstByStateOrderByIdDesc(String state);
    /** 某任务定义按 run_mode 过滤后的最近一个实例（id=UUIDv7 时间序，降序取最新）——供前端重开续接运行态。 */
    Optional<TaskInstance> findFirstByTaskIdAndRunModeOrderByIdDesc(Long taskId, String runMode);
    List<TaskInstance> findByRunMode(String runMode);
    List<TaskInstance> findByWorkflowInstanceId(UUID workflowInstanceId);

    /** 036 项目隔离：按项目 + runMode 过滤实例。 */
    List<TaskInstance> findByProjectIdAndRunMode(Long projectId, String runMode);
    /** 036 项目隔离：按项目 + 状态过滤实例。 */
    List<TaskInstance> findByProjectIdAndState(Long projectId, String state);
    /** 036 项目隔离：按项目过滤全部实例。 */
    List<TaskInstance> findByProjectId(Long projectId);
    /** 036 项目隔离：按项目 + taskId + runMode 过滤最近实例。 */
    Optional<TaskInstance> findFirstByTaskIdAndRunModeAndProjectIdOrderByIdDesc(Long taskId, String runMode, Long projectId);
}
