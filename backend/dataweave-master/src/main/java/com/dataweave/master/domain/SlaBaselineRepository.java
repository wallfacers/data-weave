package com.dataweave.master.domain;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * SLA 基线仓储（task 5.3）。
 */
public interface SlaBaselineRepository extends CrudRepository<SlaBaseline, Long> {

    /** 按 workflow + biz_date 查找（排除 TEST）。 */
    Optional<SlaBaseline> findByWorkflowIdAndBizDate(Long workflowId, String bizDate);

    /** 某工作流最近 N 次成功的就绪时间（用于计算基线），排除 TEST 实例（无 workflow_instance_id 的不算）。 */
    @Query("SELECT ready_at FROM sla_baseline WHERE workflow_id = :workflowId "
            + "AND ready_at IS NOT NULL AND deleted = 0 "
            + "ORDER BY ready_at DESC LIMIT :limit")
    List<LocalDateTime> recentReadyTimes(Long workflowId, int limit);

    /** 最近破线记录。 */
    @Query("SELECT * FROM sla_baseline WHERE breached = 1 AND deleted = 0 "
            + "ORDER BY ready_at DESC LIMIT :limit")
    List<SlaBaseline> recentBreaches(int limit);
}
