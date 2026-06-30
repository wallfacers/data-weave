package com.dataweave.master.quality.domain;

import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

/**
 * {@link QualityCheckResult} 仓储。按 {@code tenantId} 隔离；下钻按 {@code runId} 列某 run 的全部断言结果。
 */
public interface QualityCheckResultRepository extends CrudRepository<QualityCheckResult, Long> {

    List<QualityCheckResult> findByTenantIdAndRunIdAndDeleted(Long tenantId, Long runId, Integer deleted);

    Optional<QualityCheckResult> findByIdAndTenantIdAndDeleted(Long id, Long tenantId, Integer deleted);

    List<QualityCheckResult> findByTenantIdAndRuleIdAndDeleted(Long tenantId, Long ruleId, Integer deleted);
}
