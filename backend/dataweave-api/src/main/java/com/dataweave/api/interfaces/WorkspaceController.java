package com.dataweave.api.interfaces;

import com.dataweave.api.infrastructure.ApiResponse;
import com.dataweave.master.application.AgentAuditService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Workspace 快照 REST 端点（workspace-persistence spec）：随对话会话（conversationId = AG-UI
 * threadId）存取前端 Workspace 状态。后端视快照为透明 blob，不解析语义。
 *
 * <p>GET 无快照返回 {@code ApiResponse(code=0, data=null)}（前端据此回落 Pinned 底座）；
 * PUT 超长（> 8000 字符，对齐列宽）返回 code=413。
 */
@RestController
@RequestMapping("/api/agent/sessions/{conversationId}/workspace")
public class WorkspaceController {

    /** 对齐 agent_session.workspace_state VARCHAR(8000)。 */
    private static final int MAX_STATE_CHARS = 8000;

    private final AgentAuditService audit;

    public WorkspaceController(AgentAuditService audit) {
        this.audit = audit;
    }

    @GetMapping
    public ApiResponse<String> get(@PathVariable String conversationId) {
        return audit.getWorkspaceState(conversationId)
                .map(ApiResponse::ok)
                .orElseGet(ApiResponse::ok);
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Void> put(@PathVariable String conversationId, @RequestBody String body) {
        if (body == null || body.length() > MAX_STATE_CHARS) {
            return ApiResponse.err(413, "workspace 状态超长（> " + MAX_STATE_CHARS + " 字符）");
        }
        audit.putWorkspaceState(conversationId, body);
        return ApiResponse.ok();
    }
}
