package com.dataweave.master.quality.domain;

import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

/**
 * {@link QualityScorecard} 仓储。{@code (tenantId, datasetRef)} 唯一（每数据集一行最新评分）。
 */
public interface QualityScorecardRepository extends CrudRepository<QualityScorecard, Long> {

    Optional<QualityScorecard> findByTenantIdAndDatasetRefAndDeleted(
            Long tenantId, String datasetRef, Integer deleted);

    List<QualityScorecard> findByTenantIdAndDeleted(Long tenantId, Integer deleted);
}
