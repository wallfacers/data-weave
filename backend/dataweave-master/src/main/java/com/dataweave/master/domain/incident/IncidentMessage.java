package com.dataweave.master.domain.incident;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 事故会话线程消息（持久化粒度，见 MessageKinds）。思考态/流式分片/工具点亮是瞬态直播事件，
 * 只走 EventBus 不落此表；语义完整后落一条消息（对应 kind 之一）。
 */
public record IncidentMessage(
        UUID id,
        UUID incidentId,
        long seq,               // 事故内递增序，SSE Last-Event-ID 续传锚点
        String kind,             // 见 MessageKinds
        String content,          // 面向人的正文（LLM 叙述按 agent locale 原文存储）
        String payloadJson,      // 结构化载荷：chips/证据引用/agentActionId/proposalId/分型
        String actor,            // ops-agent | 用户名(服务端认定) | system
        String actorName,        // 070：发言者显示名(displayName)；Agent/system/存量消息为空
        LocalDateTime createdAt
) {
}
