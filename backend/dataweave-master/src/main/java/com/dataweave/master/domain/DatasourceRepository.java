package com.dataweave.master.domain;

import org.springframework.data.repository.CrudRepository;
import java.util.List;

public interface DatasourceRepository extends CrudRepository<Datasource, Long> {

    List<Datasource> findByProjectId(Long projectId);

    List<Datasource> findByTenantIdAndProjectIdAndDeleted(Long tenantId, Long projectId, Integer deleted);

    boolean existsByProjectIdAndNameAndDeletedAndIdNot(Long projectId, String name, Integer deleted, Long id);

    boolean existsByProjectIdAndNameAndDeleted(Long projectId, String name, Integer deleted);

    /** 统计绑定某驱动 jar 的数据源数（删除 driver_jars 资产前引用校验）。 */
    long countByDriverJarIdAndDeleted(Long driverJarId, Integer deleted);
}
