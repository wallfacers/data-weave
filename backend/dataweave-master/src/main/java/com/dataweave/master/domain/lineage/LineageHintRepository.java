package com.dataweave.master.domain.lineage;

import java.util.List;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

/** 041 未解析提示仓储：replace-per-task（先删后插）+ 按任务查询。 */
public interface LineageHintRepository extends CrudRepository<LineageUnresolvedHint, Long> {

    List<LineageUnresolvedHint> findByTenantIdAndProjectIdAndTaskDefIdOrderByIdAsc(
            Long tenantId, Long projectId, Long taskDefId);

    @Modifying
    @Query("DELETE FROM lineage_unresolved_hint WHERE tenant_id=:tenantId AND project_id=:projectId AND task_def_id=:taskDefId")
    void deleteForTask(@Param("tenantId") Long tenantId, @Param("projectId") Long projectId,
                       @Param("taskDefId") Long taskDefId);
}
