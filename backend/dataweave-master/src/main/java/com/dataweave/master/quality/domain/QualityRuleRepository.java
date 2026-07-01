package com.dataweave.master.quality.domain;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * {@link QualityRule} 仓储（纯 Spring Data JDBC，跟随全域范式：无 JdbcTemplate Impl）。
 * 全部查询按 {@code tenantId} + 软删隔离（FR-010）。
 */
public interface QualityRuleRepository extends CrudRepository<QualityRule, Long> {

    List<QualityRule> findByTenantIdAndDeleted(Long tenantId, Integer deleted);

    /** 036: 按项目隔离的查询方法。 */
    List<QualityRule> findByTenantIdAndProjectIdAndDeleted(Long tenantId, Long projectId, Integer deleted);

    Optional<QualityRule> findByIdAndTenantIdAndDeleted(Long id, Long tenantId, Integer deleted);

    Optional<QualityRule> findByIdAndTenantIdAndProjectIdAndDeleted(Long id, Long tenantId, Long projectId, Integer deleted);

    /** POST_TASK 门禁钩子查绑定某任务且启用的规则。 */
    List<QualityRule> findByTenantIdAndBoundTaskIdAndEnabledAndDeleted(
            Long tenantId, Long boundTaskId, Integer enabled, Integer deleted);

    List<QualityRule> findByTenantIdAndDatasetRefAndDeleted(Long tenantId, String datasetRef, Integer deleted);

    List<QualityRule> findByTenantIdAndProjectIdAndDatasetRefAndDeleted(Long tenantId, Long projectId, String datasetRef, Integer deleted);

    /** 独立调度入口扫启用且配了 cron 的规则（{@code schedule_cron IS NOT NULL} 派生查询难表达，走 @Query）。 */
    @Query("SELECT * FROM quality_rule"
            + " WHERE tenant_id = :tenantId AND deleted = 0 AND enabled = 1 AND schedule_cron IS NOT NULL")
    List<QualityRule> findScheduledRules(@Param("tenantId") Long tenantId);

    /** 036: 项目隔离的调度规则查询。 */
    @Query("SELECT * FROM quality_rule"
            + " WHERE tenant_id = :tenantId AND project_id = :projectId AND deleted = 0 AND enabled = 1 AND schedule_cron IS NOT NULL")
    List<QualityRule> findScheduledRulesByProject(@Param("tenantId") Long tenantId, @Param("projectId") Long projectId);
}
