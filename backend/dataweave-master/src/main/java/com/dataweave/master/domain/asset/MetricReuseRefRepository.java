package com.dataweave.master.domain.asset;

import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface MetricReuseRefRepository extends CrudRepository<MetricReuseRef, Long> {

    /** 全量复用边（项目内）：构建有向图做防环可达性检查。 */
    List<MetricReuseRef> findByTenantIdAndProjectIdAndDeleted(Long tenantId, Long projectId, Integer deleted);

    List<MetricReuseRef> findByTenantIdAndProjectIdAndListingIdAndDeleted(
            Long tenantId, Long projectId, Long listingId, Integer deleted);
}
