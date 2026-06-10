package com.dataweave.master.domain;

import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

public interface WorkerNodeRepository extends CrudRepository<WorkerNode, Long> {

    Optional<WorkerNode> findByNodeCode(String nodeCode);

    List<WorkerNode> findByStatus(String status);
}
