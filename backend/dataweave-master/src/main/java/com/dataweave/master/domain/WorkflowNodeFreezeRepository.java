package com.dataweave.master.domain;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.UUID;

/**
 * 节点级冻结 overlay 仓储。物化时按「定义级（instance_id 空）∪ 该实例实例级」取 frozen 节点。
 */
public interface WorkflowNodeFreezeRepository extends CrudRepository<WorkflowNodeFreeze, Long> {

    /** 某工作流全部未删冻结记录（定义级 + 各实例级），列表/管理用。 */
    List<WorkflowNodeFreeze> findByWorkflowIdAndDeleted(Long workflowId, Integer deleted);

    /** 定义级冻结：instance_id 为空、frozen=1、未删。 */
    @Query("SELECT * FROM workflow_node_freeze WHERE workflow_id = :wf "
            + "AND instance_id IS NULL AND frozen = 1 AND deleted = 0")
    List<WorkflowNodeFreeze> findDefinitionFrozen(@Param("wf") Long workflowId);

    /** 实例级冻结：instance_id = 指定实例、frozen=1、未删。 */
    @Query("SELECT * FROM workflow_node_freeze WHERE workflow_id = :wf "
            + "AND instance_id = :inst AND frozen = 1 AND deleted = 0")
    List<WorkflowNodeFreeze> findInstanceFrozen(@Param("wf") Long workflowId, @Param("inst") UUID instanceId);

    /** 定位某节点的定义级记录（upsert 用），instance_id 为空。 */
    @Query("SELECT * FROM workflow_node_freeze WHERE workflow_id = :wf "
            + "AND node_key = :nk AND instance_id IS NULL AND deleted = 0 LIMIT 1")
    WorkflowNodeFreeze findDefinitionRow(@Param("wf") Long workflowId, @Param("nk") String nodeKey);

    /** 定位某节点在某实例的实例级记录（upsert 用）。 */
    @Query("SELECT * FROM workflow_node_freeze WHERE workflow_id = :wf "
            + "AND node_key = :nk AND instance_id = :inst AND deleted = 0 LIMIT 1")
    WorkflowNodeFreeze findInstanceRow(@Param("wf") Long workflowId, @Param("nk") String nodeKey,
                                       @Param("inst") UUID instanceId);
}
