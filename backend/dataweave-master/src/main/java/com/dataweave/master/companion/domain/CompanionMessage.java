package com.dataweave.master.companion.domain;

import java.time.LocalDateTime;

/**
 * 管家会话消息（{@code companion_message} 表）。
 *
 * <p>两种会话：{@code reportId=null}——全局会话（底部输入框）；{@code reportId!=null}——锚定该汇报的
 * 上下文会话（FR-013，卡片内迷你对话，后端注入该汇报巡检上下文到 brain 会话）。
 *
 * <p>发言者身份服务端认定（沿 070 监督席标准）：{@code actor}=username（稳定标识），
 * {@code actorName}=displayName（可变）；{@code brainSessionId} 对应 workhorse session（排障/续聊）。
 * 流式增量（delta）是瞬态 SSE 事件不落此表，语义完整后落一条 AGENT 消息。
 */
public record CompanionMessage(
        long id,
        long tenantId,
        long projectId,
        Long reportId,              // NULL=全局会话；非 NULL=锚定该汇报的上下文会话
        String role,                // 见 CompanionRoles
        String actor,               // 用户名(服务端认定) | companion-agent | system
        String actorName,           // 发言者显示名（070 身份标准延续）
        String content,             // Markdown 正文
        String brainSessionId,      // 对应 workhorse session（排障/续聊）
        LocalDateTime createdAt
) {
}
