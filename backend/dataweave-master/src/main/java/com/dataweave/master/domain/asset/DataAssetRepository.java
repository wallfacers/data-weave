package com.dataweave.master.domain.asset;

import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

/**
 * 数据资产仓储。基础 CRUD + 去重/隔离派生查询；分面搜索走 JdbcTemplate（见 AssetSearchService）。
 */
public interface DataAssetRepository extends CrudRepository<DataAsset, Long> {

    /** 去重检查（FR-010）：同 (tenant, project, datasource, qualifiedName) 是否已编目。 */
    Optional<DataAsset> findFirstByTenantIdAndProjectIdAndDatasourceIdAndQualifiedNameAndDeleted(
            Long tenantId, Long projectId, Long datasourceId, String qualifiedName, Integer deleted);

    /** 租户隔离读：按 id + tenant（防跨租户越权）。 */
    Optional<DataAsset> findByIdAndTenantIdAndDeleted(Long id, Long tenantId, Integer deleted);

    List<DataAsset> findByTenantIdAndProjectIdAndDeleted(Long tenantId, Long projectId, Integer deleted);
}
