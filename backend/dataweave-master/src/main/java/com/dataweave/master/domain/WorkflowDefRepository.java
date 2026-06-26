package com.dataweave.master.domain;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;

public interface WorkflowDefRepository extends CrudRepository<WorkflowDef, Long> {
    List<WorkflowDef> findByProjectId(Long projectId);
    List<WorkflowDef> findByScheduleType(String scheduleType);
    List<WorkflowDef> findByScheduleTypeAndStatusAndDeleted(String scheduleType, String status, Integer deleted);

    /**
     * 扫描到期工作流（非分片模式，Batch A）。捞取条件：
     * 未删 + ONLINE + schedule_type = 指定类型 + next_trigger_time 非空且 ≤ 截止时刻。
     */
    @Query("SELECT * FROM workflow_def"
            + " WHERE deleted = 0"
            + " AND status = :status"
            + " AND schedule_type = :scheduleType"
            + " AND next_trigger_time IS NOT NULL"
            + " AND next_trigger_time <= :nextTriggerTimeLe"
            + " ORDER BY next_trigger_time ASC")
    List<WorkflowDef> findScannable(@Param("scheduleType") String scheduleType,
                                    @Param("status") String status,
                                    @Param("nextTriggerTimeLe") LocalDateTime nextTriggerTimeLe);

    /**
     * 扫描到期工作流（多类型，US4+）。scheduleTypes 传入列表以展开 IN 子句。
     */
    @Query("SELECT * FROM workflow_def"
            + " WHERE deleted = 0"
            + " AND status = :status"
            + " AND schedule_type IN (:scheduleTypes)"
            + " AND next_trigger_time IS NOT NULL"
            + " AND next_trigger_time <= :nextTriggerTimeLe"
            + " ORDER BY next_trigger_time ASC")
    List<WorkflowDef> findScannableByTypes(@Param("scheduleTypes") List<String> scheduleTypes,
                                           @Param("status") String status,
                                           @Param("nextTriggerTimeLe") LocalDateTime nextTriggerTimeLe);

    /**
     * 扫描到期工作流（分片模式，Batch B）。在非分片基础上追加 MOD(id, shardCount) = shardIndex。
     */
    @Query("SELECT * FROM workflow_def"
            + " WHERE deleted = 0"
            + " AND status = :status"
            + " AND schedule_type IN (:scheduleTypes)"
            + " AND next_trigger_time IS NOT NULL"
            + " AND next_trigger_time <= :nextTriggerTimeLe"
            + " AND MOD(id, :shardCount) = :shardIndex"
            + " ORDER BY next_trigger_time ASC")
    List<WorkflowDef> findScannableSharded(@Param("scheduleTypes") List<String> scheduleTypes,
                                           @Param("status") String status,
                                           @Param("nextTriggerTimeLe") LocalDateTime nextTriggerTimeLe,
                                           @Param("shardCount") int shardCount,
                                           @Param("shardIndex") int shardIndex);
}
