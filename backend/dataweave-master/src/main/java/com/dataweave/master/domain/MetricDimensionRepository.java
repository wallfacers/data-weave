package com.dataweave.master.domain;

import org.springframework.data.repository.CrudRepository;
import java.util.List;

public interface MetricDimensionRepository extends CrudRepository<MetricDimension, Long> {
    List<MetricDimension> findByMetricTypeAndMetricId(String metricType, Long metricId);
}
