package com.dataweave.master.domain;

import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface AgentRunRepository extends CrudRepository<AgentRun, Long> {

    List<AgentRun> findBySessionIdOrderByIdAsc(Long sessionId);
}
