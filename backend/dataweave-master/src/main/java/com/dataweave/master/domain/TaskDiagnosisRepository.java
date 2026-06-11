package com.dataweave.master.domain;

import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaskDiagnosisRepository extends CrudRepository<TaskDiagnosis, Long> {

    List<TaskDiagnosis> findByStatus(String status);

    Optional<TaskDiagnosis> findFirstByTaskInstanceIdOrderByIdDesc(UUID taskInstanceId);
}
