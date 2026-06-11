package com.dataweave.master.domain;

import org.springframework.data.repository.CrudRepository;
import java.util.List;

public interface WorkflowDefRepository extends CrudRepository<WorkflowDef, Long> {
    List<WorkflowDef> findByProjectId(Long projectId);
    List<WorkflowDef> findByScheduleType(String scheduleType);
    List<WorkflowDef> findByScheduleTypeAndStatusAndDeleted(String scheduleType, String status, Integer deleted);
}
