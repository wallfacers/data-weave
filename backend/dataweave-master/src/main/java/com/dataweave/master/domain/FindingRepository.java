package com.dataweave.master.domain;

import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

public interface FindingRepository extends CrudRepository<Finding, Long> {

    /** 举手台列表：按状态集合（OPEN/ANNOUNCED）取，id 降序。 */
    List<Finding> findByStatusInOrderByIdDesc(List<String> statuses);

    /** 去重查询：同 (source,targetType,targetId) 且仍在给定状态集合内的最新一条。 */
    Optional<Finding> findFirstBySourceAndTargetTypeAndTargetIdAndStatusInOrderByIdDesc(
            String source, String targetType, String targetId, List<String> statuses);

    List<Finding> findByStatus(String status);

    /** 是否已存在该 (source,targetType,targetId) 的任意发现（含已 RESOLVED）——巡检器据此跳过已处理目标。 */
    boolean existsBySourceAndTargetTypeAndTargetId(String source, String targetType, String targetId);
}
