package com.dataweave.master.domain;

import org.springframework.data.repository.CrudRepository;
import java.util.List;
import java.util.Optional;

public interface DatasourceRepository extends CrudRepository<Datasource, Long> {

    List<Datasource> findByProjectId(Long projectId);

    List<Datasource> findByTenantIdAndProjectIdAndDeleted(Long tenantId, Long projectId, Integer deleted);

    /** push 数据源逻辑名 → id 解析（D3）。 */
    Optional<Datasource> findByProjectIdAndName(Long projectId, String name);

    boolean existsByProjectIdAndNameAndDeletedAndIdNot(Long projectId, String name, Integer deleted, Long id);

    boolean existsByProjectIdAndNameAndDeleted(Long projectId, String name, Integer deleted);

    /** 统计绑定某驱动 jar 的数据源数（删除 driver_jars 资产前引用校验）。 */
    long countByDriverJarIdAndDeleted(Long driverJarId, Integer deleted);
}
