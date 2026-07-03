package com.dataweave.master.domain.lineage;

import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.CrudRepository;

/** 041 人工修正裁决仓储：语义键唯一（uk_lineage_correction_key），UPSERT 由 find-then-save 实现。 */
public interface LineageCorrectionRepository extends CrudRepository<LineageEdgeCorrection, Long> {

    Optional<LineageEdgeCorrection> findByTenantIdAndProjectIdAndTaskDefIdAndDirectionAndTableKeyAndColumnKey(
            Long tenantId, Long projectId, Long taskDefId, String direction, String tableKey, String columnKey);

    List<LineageEdgeCorrection> findByTenantIdAndProjectIdAndTaskDefIdOrderByIdAsc(
            Long tenantId, Long projectId, Long taskDefId);

    List<LineageEdgeCorrection> findByTenantIdAndProjectIdAndTaskDefIdIn(
            Long tenantId, Long projectId, java.util.Collection<Long> taskDefIds);
}
