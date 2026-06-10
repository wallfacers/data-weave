package com.dataweave.master.domain;

import org.springframework.data.repository.CrudRepository;
import java.util.List;
import java.util.Optional;

public interface AtomicMetricRepository extends CrudRepository<AtomicMetric, Long> {
    List<AtomicMetric> findByProjectId(Long projectId);
    Optional<AtomicMetric> findFirstByCodeOrderByVersionNoDesc(String code);
    Optional<AtomicMetric> findFirstByProjectIdAndCodeOrderByVersionNoDesc(Long projectId, String code);
}
