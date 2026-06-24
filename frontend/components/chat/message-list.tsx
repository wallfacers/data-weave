/** 消息列表：user 右对齐气泡 / assistant 左对齐按 part 渲染，自动滚到底。 */
"use client"

import { useEffect, useRef } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import { SparklesIcon } from "@hugeicons/core-free-icons"

import { useChatStore } from "@/lib/chat/store"
import { MessagePartView } from "./message-part"
import type { ChatMessage } from "@/lib/chat/types"

export function MessageList() {
  const t = useTranslations("chat")
  const activeId = useChatStore((s) => s.activeId)
  const runtime = useChatStore((s) =>
    activeId ? s.runtimes[activeId] : undefined,
  )
  const scrollRef = useRef<HTMLDivElement>(null)
  const messages = runtime?.messages ?? []

  // 新消息 / 流式增量 → 滚到底
  useEffect(() => {
    const el = scrollRef.current
    if (el) el.scrollTop = el.scrollHeight
  }, [messages])

  return (
    <div ref={scrollRef} className="min-h-0 flex-1 overflow-y-auto">
      {messages.length === 0 ? (
        <div className="flex h-full flex-col items-center justify-center gap-3 p-6 text-center">
          <div className="flex size-10 items-center justify-center rounded-xl bg-primary/10 text-primary">
            <HugeiconsIcon icon={SparklesIcon} className="size-5" />
          </div>
          <p className="max-w-[16rem] text-sm text-muted-foreground">
            {t("emptyHint")}
          </p>
        </div>
      ) : (
        <div className="flex flex-col gap-4 p-4">
          {messages.map((m, i) => (
            <MessageItem
              key={m.id}
              message={m}
              isLast={i === messages.length - 1}
              streamingIds={runtime?.streaming}
            />
          ))}
        </div>
      )}
    </div>
  )
}

function MessageItem({
  message,
  isLast,
  streamingIds,
}: {
  message: ChatMessage
  isLast: boolean
  streamingIds?: Set<string>
}) {
  const t = useTranslations("chat")

  if (message.role === "user") {
    const text = message.parts.find((p) => p.type === "text")
    return (
      <div className="flex justify-end">
        <div className="max-w-[85%] whitespace-pre-wrap break-words rounded-2xl rounded-br-sm bg-primary px-3 py-2 text-sm text-primary-foreground">
          {text?.type === "text" ? text.content : ""}
        </div>
      </div>
    )
  }

  const streaming = streamingIds?.has(message.id) ?? false
  return (
    <div className="flex flex-col gap-2">
      {message.parts.map((p, idx) => {
        const partStreaming =
          streaming &&
          isLast &&
          idx === message.parts.length - 1 &&
          p.type === "text"
        return <MessagePartView key={idx} part={p} streaming={partStreaming} />
      })}
      {message.interrupted && (
        <span className="text-xs italic text-muted-foreground">
          {t("interrupted")}
        </span>
      )}
    </div>
  )
}
