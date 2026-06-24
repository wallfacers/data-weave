package com.dataweave.api.interfaces;

import com.dataweave.api.infrastructure.ApiResponse;
import com.dataweave.master.application.AgentSessionService;
import com.dataweave.master.domain.AgentChatMessage;
import com.dataweave.master.domain.AgentChatSession;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 自有聊天台多会话 REST 端点（agent-chat-shell）：列表/新建/删除/历史重水合 + 消息追加。
 */
@RestController
@RequestMapping("/api/agent/sessions")
public class AgentSessionController {

    private final AgentSessionService sessionService;

    public AgentSessionController(AgentSessionService sessionService) {
        this.sessionService = sessionService;
    }

    @GetMapping
    public ApiResponse<List<AgentChatSession>> list() {
        return ApiResponse.ok(sessionService.list());
    }

    @PostMapping
    public ApiResponse<AgentChatSession> create(@RequestBody(required = false) CreateRequest body) {
        return ApiResponse.ok(sessionService.create(body != null ? body.title() : null));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        sessionService.delete(id);
        return ApiResponse.ok(null);
    }

    @GetMapping("/{id}/history")
    public ApiResponse<List<AgentChatMessage>> history(@PathVariable Long id) {
        return ApiResponse.ok(sessionService.history(id));
    }

    @PostMapping("/{id}/messages")
    public ApiResponse<AgentChatMessage> appendMessage(@PathVariable Long id,
                                                       @RequestBody MessageRequest body) {
        return ApiResponse.ok(sessionService.appendMessage(id, body.role(), body.partsJson()));
    }

    public record CreateRequest(String title) {
    }

    public record MessageRequest(String role, String partsJson) {
    }
}
