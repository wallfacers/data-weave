package com.dataweave.master.domain;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface MetricLineageRepository extends CrudRepository<MetricLineage, Long> {

    @Query("SELECT * FROM metric_lineage WHERE metric_id = :metricId")
    List<MetricLineage> findByMetricId(Long metricId);
}
