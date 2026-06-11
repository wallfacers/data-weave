package com.dataweave.master.domain;

import org.springframework.data.repository.CrudRepository;

import java.time.LocalDateTime;
import java.util.Optional;

public interface CronFireRepository extends CrudRepository<CronFire, Long> {

    Optional<CronFire> findByWorkflowIdAndScheduledFireTime(Long workflowId, LocalDateTime scheduledFireTime);
}
