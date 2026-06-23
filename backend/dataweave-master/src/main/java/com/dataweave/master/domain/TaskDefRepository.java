package com.dataweave.master.domain;

import org.springframework.data.repository.CrudRepository;
import java.util.List;

public interface TaskDefRepository extends CrudRepository<TaskDef, Long> {
    List<TaskDef> findByProjectId(Long projectId);

    long countByDatasourceIdAndDeleted(Long datasourceId, Integer deleted);

    long countByTargetDatasourceIdAndDeleted(Long targetDatasourceId, Integer deleted);
}
