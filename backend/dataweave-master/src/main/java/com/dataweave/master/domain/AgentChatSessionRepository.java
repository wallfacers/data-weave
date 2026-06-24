package com.dataweave.master.domain;

import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface AgentChatSessionRepository extends CrudRepository<AgentChatSession, Long> {

    /** 未删除会话，最近更新优先。 */
    List<AgentChatSession> findByDeletedOrderByUpdatedAtDesc(Integer deleted);
}
