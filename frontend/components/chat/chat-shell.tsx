/** 聊天台主体：挂载时 init store（加载会话/发现、订阅 agent-stream），消息列表 + 输入框。 */
"use client"

import { useEffect } from "react"

import { useChatStore } from "@/lib/chat/store"
import { MessageList } from "./message-list"
import { ChatInput } from "./chat-input"
import type { AgentPageContext } from "@/lib/chat/types"

export function ChatShell({ context }: { context?: AgentPageContext }) {
  const init = useChatStore((s) => s.init)
  useEffect(() => {
    void init()
  }, [init])

  return (
    <div className="flex h-full min-h-0 flex-col">
      <MessageList />
      <ChatInput context={context} />
    </div>
  )
}
