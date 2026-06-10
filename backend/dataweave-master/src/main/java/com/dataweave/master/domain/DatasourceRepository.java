package com.dataweave.master.domain;

import org.springframework.data.repository.CrudRepository;
import java.util.List;

public interface DatasourceRepository extends CrudRepository<Datasource, Long> {

    List<Datasource> findByProjectId(Long projectId);
}
