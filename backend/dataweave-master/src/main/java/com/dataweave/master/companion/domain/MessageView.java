package com.dataweave.master.companion.domain;

import java.time.LocalDateTime;

/**
 * 管家会话消息视图（契约 MessageView，SSE/REST 共用）。
 *
 * <pre>
 * MessageView = {id, reportId?, role, actorName, content, createdAt}
 * </pre>
 * {@code reportId} 为空即全局会话；非空即锚定该汇报的上下文会话。发言者身份服务端认定，仅暴露显示名。
 */
public record MessageView(
        long id,
        Long reportId,
        String role,
        String actorName,
        String content,
        LocalDateTime createdAt
) {
    public static MessageView from(CompanionMessage m) {
        return new MessageView(m.id(), m.reportId(), m.role(), m.actorName(), m.content(), m.createdAt());
    }
}
