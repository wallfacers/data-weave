package com.dataweave.master.domain;

import org.springframework.data.repository.CrudRepository;
import java.util.List;
import java.util.Optional;

public interface DatasourceTypeRepository extends CrudRepository<DatasourceType, Long> {

    Optional<DatasourceType> findByCode(String code);

    List<DatasourceType> findByCategoryAndDeletedOrderByCodeAsc(String category, Integer deleted);

    List<DatasourceType> findByDeletedOrderByCategoryAscCodeAsc(Integer deleted);
}
