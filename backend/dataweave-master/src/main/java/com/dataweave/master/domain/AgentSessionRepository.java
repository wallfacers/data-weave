package com.dataweave.master.domain;

import org.springframework.data.repository.CrudRepository;

import java.util.Optional;

public interface AgentSessionRepository extends CrudRepository<AgentSession, Long> {

    Optional<AgentSession> findFirstByConversationIdOrderByIdDesc(String conversationId);
}
