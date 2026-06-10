package com.dataweave.master.domain;

import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface AgentStepRepository extends CrudRepository<AgentStep, Long> {

    List<AgentStep> findByRunIdOrderBySeqAsc(Long runId);

    List<AgentStep> findByRunIdInOrderByIdAsc(List<Long> runIds);
}
