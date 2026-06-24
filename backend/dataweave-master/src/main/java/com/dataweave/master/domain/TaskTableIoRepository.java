package com.dataweave.master.domain;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface TaskTableIoRepository extends CrudRepository<TaskTableIo, Long> {
    List<TaskTableIo> findByTaskDefId(Long taskDefId);

    List<TaskTableIo> findByTableId(Long tableId);

    /** 重新发布血缘前清除该任务旧边（替换语义）。 */
    @Modifying
    @Query("DELETE FROM task_table_io WHERE task_def_id = :taskDefId")
    void deleteByTaskDefId(@Param("taskDefId") Long taskDefId);
}
