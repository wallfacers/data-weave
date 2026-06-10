package com.dataweave.master.domain;

import org.springframework.data.repository.CrudRepository;
import java.util.List;
import java.util.Optional;

public interface TaskDefVersionRepository extends CrudRepository<TaskDefVersion, Long> {
    List<TaskDefVersion> findByTaskIdOrderByVersionNoDesc(Long taskId);
    Optional<TaskDefVersion> findByTaskIdAndVersionNo(Long taskId, Integer versionNo);
}
