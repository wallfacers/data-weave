package com.dataweave.master.quality.domain;

import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link QualityCheckRun} 仓储。按 {@code tenantId} 隔离；POST_TASK 入口按 {@code taskInstanceId} 回查关联。
 */
public interface QualityCheckRunRepository extends CrudRepository<QualityCheckRun, Long> {

    List<QualityCheckRun> findByTenantIdAndDeleted(Long tenantId, Integer deleted);

    /** 036: 按项目隔离的查询方法。 */
    List<QualityCheckRun> findByTenantIdAndProjectIdAndDeleted(Long tenantId, Long projectId, Integer deleted);

    Optional<QualityCheckRun> findByIdAndTenantIdAndDeleted(Long id, Long tenantId, Integer deleted);

    Optional<QualityCheckRun> findByIdAndTenantIdAndProjectIdAndDeleted(Long id, Long tenantId, Long projectId, Integer deleted);

    List<QualityCheckRun> findByTenantIdAndTaskInstanceIdAndDeleted(
            Long tenantId, UUID taskInstanceId, Integer deleted);

    List<QualityCheckRun> findByTenantIdAndDatasetRefAndDeleted(Long tenantId, String datasetRef, Integer deleted);

    List<QualityCheckRun> findByTenantIdAndProjectIdAndDatasetRefAndDeleted(Long tenantId, Long projectId, String datasetRef, Integer deleted);
}
