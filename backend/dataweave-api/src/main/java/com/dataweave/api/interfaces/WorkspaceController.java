package com.dataweave.api.interfaces;

import com.dataweave.api.infrastructure.ApiResponse;
import com.dataweave.master.application.WorkspaceSnapshotService;
import com.dataweave.master.i18n.BizException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Workspace 快照 REST 端点（workspace-persistence）：按客户端键存取前端 Workspace 状态。
 * conversationId 现为前端 localStorage 自生成的不透明 clientKey，后端视快照为透明 blob，不解析语义。
 *
 * <p>GET 无快照返回 {@code ApiResponse(code=0, data=null)}（前端据此回落 Pinned 底座）；
 * PUT 超长（> 8000 字符，对齐列宽）返回 code=413。
 */
@RestController
@RequestMapping("/api/agent/sessions/{conversationId}/workspace")
public class WorkspaceController {

    /** 对齐 workspace_snapshot.snapshot_json VARCHAR(8000)。 */
    private static final int MAX_STATE_CHARS = 8000;

    private final WorkspaceSnapshotService snapshots;

    public WorkspaceController(WorkspaceSnapshotService snapshots) {
        this.snapshots = snapshots;
    }

    @GetMapping
    public ApiResponse<String> get(@PathVariable String conversationId) {
        return snapshots.getWorkspaceState(conversationId)
                .map(ApiResponse::ok)
                .orElseGet(ApiResponse::ok);
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Void> put(@PathVariable String conversationId, @RequestBody String body) {
        if (body == null || body.length() > MAX_STATE_CHARS) {
            throw new BizException("workspace.state.too_long", MAX_STATE_CHARS).withHttpStatus(413);
        }
        snapshots.putWorkspaceState(conversationId, body);
        return ApiResponse.ok();
    }
}
