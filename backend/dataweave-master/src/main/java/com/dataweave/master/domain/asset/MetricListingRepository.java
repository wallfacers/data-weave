package com.dataweave.master.domain.asset;

import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

public interface MetricListingRepository extends CrudRepository<MetricListing, Long> {

    Optional<MetricListing> findFirstByTenantIdAndProjectIdAndMetricTypeAndMetricIdAndDeleted(
            Long tenantId, Long projectId, String metricType, Long metricId, Integer deleted);

    Optional<MetricListing> findByIdAndTenantIdAndDeleted(Long id, Long tenantId, Integer deleted);

    List<MetricListing> findByTenantIdAndProjectIdAndDeleted(Long tenantId, Long projectId, Integer deleted);
}
