package com.dataweave.master.domain;

import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

/**
 * 驱动 jar 资产仓储（datasource-driver-isolation）。
 *
 * <p>按 {@code tenant + sha256} 去重查询；按 {@code typeCode + status} 列出某类型可用资产。
 */
public interface DriverJarRepository extends CrudRepository<DriverJar, Long> {

    /** 按租户 + sha256 查（去重：上传同内容 jar 复用）。 */
    Optional<DriverJar> findByTenantIdAndSha256AndDeleted(Long tenantId, String sha256, Integer deleted);

    /** 按类型 + 状态列出可用资产（如某 typeCode 下所有 ACTIVE）。 */
    List<DriverJar> findByTypeCodeAndStatusAndDeletedOrderByCreatedAtDesc(String typeCode, String status, Integer deleted);
}
