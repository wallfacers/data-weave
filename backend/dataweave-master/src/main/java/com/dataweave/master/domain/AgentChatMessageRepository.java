package com.dataweave.master.domain;

import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface AgentChatMessageRepository extends CrudRepository<AgentChatMessage, Long> {

    /** 某会话的消息，按 id 升序（时间序）重水合。 */
    List<AgentChatMessage> findBySessionIdOrderByIdAsc(Long sessionId);

    void deleteBySessionId(Long sessionId);
}
