/** 聊天台主体：挂载时 init store（加载会话/发现、订阅 agent-stream），消息列表 + 输入框。
 *  空会话（无任何消息）时：欢迎语 + 输入框成组居中（输入框紧贴欢迎语下方）；
 *  一旦有消息：输入框立刻回到底部、消息列表占满上方。 */
"use client"

import { useEffect } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import { SparklesIcon } from "@hugeicons/core-free-icons"

import { useChatStore } from "@/lib/chat/store"
import { MessageList } from "./message-list"
import { ChatInput } from "./chat-input"
import type { AgentPageContext } from "@/lib/chat/types"

export function ChatShell({ context }: { context?: AgentPageContext }) {
  const t = useTranslations("chat")
  const init = useChatStore((s) => s.init)
  const hasMessages = useChatStore((s) => {
    const rt = s.activeId ? s.runtimes[s.activeId] : undefined
    return (rt?.messages.length ?? 0) > 0
  })
  useEffect(() => {
    void init()
  }, [init])

  // 空会话：欢迎语 + 输入框成组，整体垂直居中。
  if (!hasMessages) {
    return (
      <div className="flex h-full min-h-0 flex-col justify-center gap-5">
        <div className="flex flex-col items-center gap-3 px-6 text-center">
          <div className="flex size-10 items-center justify-center rounded-xl bg-primary/10 text-primary">
            <HugeiconsIcon icon={SparklesIcon} className="size-5" />
          </div>
          <p className="max-w-[18rem] text-sm text-muted-foreground">{t("emptyHint")}</p>
        </div>
        <ChatInput context={context} />
      </div>
    )
  }

  // 有消息：列表占满 + 输入框固定底部。
  return (
    <div className="flex h-full min-h-0 flex-col">
      <MessageList />
      <ChatInput context={context} />
    </div>
  )
}
