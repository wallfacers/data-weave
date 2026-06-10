package com.dataweave.master.domain;

import org.springframework.data.repository.CrudRepository;
import java.util.List;

public interface MetricLineageRepository extends CrudRepository<MetricLineage, Long> {
    List<MetricLineage> findByMetricTypeAndMetricId(String metricType, Long metricId);
    List<MetricLineage> findByMetricId(Long metricId);
}
