package com.dataweave.master.domain;

import org.springframework.data.repository.CrudRepository;
import java.util.List;

public interface TaskDefRepository extends CrudRepository<TaskDef, Long> {
    List<TaskDef> findByProjectId(Long projectId);

    /** E 子特性：按租户过滤有效任务定义（MCP query_task_definitions 隔离回补）。 */
    List<TaskDef> findByTenantIdAndDeleted(Long tenantId, Integer deleted);

    long countByDatasourceIdAndDeleted(Long datasourceId, Integer deleted);

    long countByTargetDatasourceIdAndDeleted(Long targetDatasourceId, Integer deleted);
}
