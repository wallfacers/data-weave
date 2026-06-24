"use client"

import { ChatShell } from "@/components/chat/chat-shell"
import type { AgentPageContext } from "@/lib/chat/types"

/**
 * Agent 对话主驾入口（proactive-agent-discovery：替换 CopilotKit v2）。
 *
 * 不再用 CopilotKit CopilotChat / HttpAgent——改为自研 ChatShell：自有消息存储
 * （lib/chat/store）+ AG-UI 流消费 + agent-stream 持久订阅（主动开口 / 发现冒泡）。
 * 逐消息页面上下文（cockpit 缺口①）经 context 透传给后端 forwardedProps.dataweave。
 */
export function AgentChat({ context }: { context?: AgentPageContext }) {
  return <ChatShell context={context} />
}

export type { AgentPageContext } from "@/lib/chat/types"
