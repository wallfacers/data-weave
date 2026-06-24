package com.dataweave.master.application;

import com.dataweave.master.domain.AgentChatMessage;
import com.dataweave.master.domain.AgentChatMessageRepository;
import com.dataweave.master.domain.AgentChatSession;
import com.dataweave.master.domain.AgentChatSessionRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 自有聊天台多会话持久化（agent-chat-shell）：会话元数据 CRUD + 消息追加/重水合。
 *
 * <p>后端只做透明存储：{@code partsJson} 是前端 MessagePart[] 的序列化 blob，后端不解释其结构。
 */
@Service
public class AgentSessionService {

    private final AgentChatSessionRepository sessionRepository;
    private final AgentChatMessageRepository messageRepository;

    public AgentSessionService(AgentChatSessionRepository sessionRepository,
                               AgentChatMessageRepository messageRepository) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
    }

    /** 未删除会话列表，最近更新优先。 */
    public List<AgentChatSession> list() {
        return sessionRepository.findByDeletedOrderByUpdatedAtDesc(0);
    }

    public AgentChatSession create(String title) {
        LocalDateTime now = LocalDateTime.now();
        AgentChatSession s = new AgentChatSession();
        s.setTenantId(1L);
        s.setProjectId(1L);
        s.setTitle(title != null && !title.isBlank() ? title : "新会话");
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        s.setDeleted(0);
        s.setVersion(0);
        return sessionRepository.save(s);
    }

    /** 软删除会话并清空其消息。 */
    public void delete(Long id) {
        sessionRepository.findById(id).ifPresent(s -> {
            s.setDeleted(1);
            s.setUpdatedAt(LocalDateTime.now());
            sessionRepository.save(s);
            messageRepository.deleteBySessionId(id);
        });
    }

    /** 追加一条消息（前端在流结束/用户发送时调用），并顶起会话的 updatedAt。 */
    public AgentChatMessage appendMessage(Long sessionId, String role, String partsJson) {
        AgentChatMessage m = new AgentChatMessage();
        m.setSessionId(sessionId);
        m.setRole(role);
        m.setPartsJson(partsJson);
        m.setCreatedAt(LocalDateTime.now());
        AgentChatMessage saved = messageRepository.save(m);
        sessionRepository.findById(sessionId).ifPresent(s -> {
            s.setUpdatedAt(LocalDateTime.now());
            sessionRepository.save(s);
        });
        return saved;
    }

    /** 会话历史（重水合）。 */
    public List<AgentChatMessage> history(Long sessionId) {
        return messageRepository.findBySessionIdOrderByIdAsc(sessionId);
    }

    public Optional<AgentChatSession> get(Long id) {
        return sessionRepository.findById(id);
    }
}
