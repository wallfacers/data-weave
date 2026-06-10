package com.dataweave.master.domain;

import org.springframework.data.repository.CrudRepository;
import java.util.List;
import java.util.Optional;

public interface DimensionRepository extends CrudRepository<Dimension, Long> {
    List<Dimension> findByProjectId(Long projectId);
    Optional<Dimension> findByProjectIdAndCode(Long projectId, String code);
}
