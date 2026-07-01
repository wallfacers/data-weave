package com.dataweave.master.domain;

import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.UUID;

public interface BackfillRunRepository extends CrudRepository<BackfillRun, UUID> {
    /** 036 项目隔离：按项目过滤补数据批次。 */
    List<BackfillRun> findByProjectId(Long projectId);
}
