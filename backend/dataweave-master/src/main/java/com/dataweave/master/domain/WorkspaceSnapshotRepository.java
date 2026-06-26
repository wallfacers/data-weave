package com.dataweave.master.domain;

import org.springframework.data.repository.CrudRepository;

import java.util.Optional;

public interface WorkspaceSnapshotRepository extends CrudRepository<WorkspaceSnapshot, Long> {

    Optional<WorkspaceSnapshot> findFirstByClientKeyOrderByIdDesc(String clientKey);
}
