package com.dataweave.master.domain;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface MasterNodeRepository extends CrudRepository<MasterNode, Long> {

    Optional<MasterNode> findByMasterCode(String masterCode);

    /** 活 master 列表：ONLINE 且心跳未超时，按 master_code 稳定排序。 */
    @Query("SELECT * FROM master_nodes"
            + " WHERE status = 'ONLINE'"
            + " AND last_heartbeat > :threshold"
            + " ORDER BY master_code ASC")
    List<MasterNode> findActive(@Param("threshold") LocalDateTime heartbeatThreshold);

    /** 将超时 master 标记为 OFFLINE。 */
    @Query("UPDATE master_nodes SET status = 'OFFLINE', updated_at = :now"
            + " WHERE status = 'ONLINE' AND last_heartbeat <= :threshold")
    void markOffline(@Param("threshold") LocalDateTime heartbeatThreshold, @Param("now") LocalDateTime now);
}
