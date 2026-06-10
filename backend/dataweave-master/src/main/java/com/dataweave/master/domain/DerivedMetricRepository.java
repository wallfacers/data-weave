package com.dataweave.master.domain;

import org.springframework.data.repository.CrudRepository;
import java.util.List;
import java.util.Optional;

public interface DerivedMetricRepository extends CrudRepository<DerivedMetric, Long> {
    List<DerivedMetric> findByProjectId(Long projectId);
    Optional<DerivedMetric> findFirstByCodeOrderByVersionNoDesc(String code);
}
