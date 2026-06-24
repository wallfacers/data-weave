package com.dataweave.master.domain;

import org.springframework.data.repository.CrudRepository;
import java.util.Optional;

public interface DataTableRepository extends CrudRepository<DataTable, Long> {
    /** 按 (datasource_id, qualified_name) 查节点（唯一）。 */
    Optional<DataTable> findFirstByDatasourceIdAndQualifiedName(Long datasourceId, String qualifiedName);
}
