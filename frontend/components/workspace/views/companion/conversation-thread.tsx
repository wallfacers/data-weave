"use client"

/**
 * 统一会话线程（US1）。
 *
 * 三角色（USER / AGENT / SYSTEM）头像区分 + 左右对齐；
 * 每条消息经既有 {@link ChatMarkdown} 渲染 Markdown；
 * 滚动区走 {@link DwScroll}（项目规范，禁手写 overflow）；
 * 空态双语提示；流式消息传递 streaming 安全闭合围栏；
 * streamingId 对应条目实时刷新；中断标记 ⌟ 视觉呈现。
 */
import { useMemo } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import { UserIcon, AiBrain01Icon, ServerStack01Icon } from "@hugeicons/core-free-icons"

import { DwScroll } from "@/components/ui/dw-scroll"
import { ChatMarkdown } from "@/components/workspace/shared/chat-markdown"
import { useCompanionStore } from "@/lib/companion/store"
import { useFormatDateTime } from "@/hooks/use-format-date-time"
import { cn } from "@/lib/utils"
import type { MessageView, MessageRole } from "@/lib/companion/types"

/* 三角色头像 */
const ROLE_ICON: Record<MessageRole, typeof UserIcon> = {
  USER: UserIcon,
  AGENT: AiBrain01Icon,
  SYSTEM: ServerStack01Icon,
}

const ROLE_AVATAR_BG: Record<MessageRole, string> = {
  USER: "bg-primary/10 text-primary",
  AGENT: "bg-info/10 text-info",
  SYSTEM: "bg-destructive/10 text-destructive",
}

/** 流式进行中标记 */
function StreamingDot() {
  return (
    <span className="ml-1.5 inline-flex items-center gap-[3px]">
      <span className="size-[5px] rounded-full bg-primary/60 motion-safe:animate-pulse" />
      <span className="size-[5px] rounded-full bg-primary/40 motion-safe:animate-pulse" style={{ animationDelay: "0.2s" }} />
      <span className="size-[5px] rounded-full bg-primary/20 motion-safe:animate-pulse" style={{ animationDelay: "0.4s" }} />
    </span>
  )
}

interface MessageBubbleProps {
  message: MessageView
  isStreaming: boolean
  formatDateTime: (iso: string | null | undefined) => string
}

function MessageBubble({ message, isStreaming, formatDateTime }: MessageBubbleProps) {
  const t = useTranslations("companion")
  const isUser = message.role === "USER"
  const isSystem = message.role === "SYSTEM"
  const Icon = ROLE_ICON[message.role]
  const avatarBg = ROLE_AVATAR_BG[message.role]

  const interrupted = message.content.endsWith("⌟")
  const displayContent = interrupted ? message.content.slice(0, -2).trimEnd() : message.content

  const timeLabel = formatDateTime(message.createdAt)

  return (
    <div
      className={cn(
        "flex gap-2.5",
        isUser ? "flex-row-reverse" : "flex-row",
      )}
    >
      {/* 头像 */}
      <div
        className={cn(
          "flex size-[28px] shrink-0 items-center justify-center rounded-full",
          avatarBg,
        )}
        title={message.actorName || message.role}
      >
        <HugeiconsIcon icon={Icon} className="size-[15px]" />
      </div>

      {/* 气泡 */}
      <div
        className={cn(
          "min-w-0 max-w-[78%] rounded-2xl px-3.5 py-2.5 text-sm leading-relaxed",
          isUser
            ? "rounded-tr-md bg-primary/10 text-foreground"
            : isSystem
              ? "rounded-tl-md border border-destructive/30 bg-destructive/5 text-foreground"
              : "rounded-tl-md bg-muted/60 text-foreground",
        )}
      >
        {/* 发送者名 + 时间 */}
        <div
          className={cn(
            "mb-1 flex items-center gap-1.5 text-[11px]",
            isUser ? "justify-end" : "justify-start",
          )}
        >
          <span className="font-medium text-muted-foreground">
            {message.actorName || (isUser ? "You" : message.role)}
          </span>
          <span className="text-muted-foreground/60">{timeLabel}</span>
        </div>

        {/* 正文 */}
        <ChatMarkdown
          content={displayContent}
          streaming={isStreaming}
        />

        {/* 流式指示 / 中断标记 */}
        {isStreaming && <StreamingDot />}
        {interrupted && !isStreaming && (
          <span className="mt-1 inline-block text-[11px] italic text-muted-foreground">
            ⌟ {t("conversation.interrupted")}
          </span>
        )}
      </div>
    </div>
  )
}

export function ConversationThread() {
  const t = useTranslations("companion")
  const messages = useCompanionStore((s) => s.messages)
  const streamingId = useCompanionStore((s) => s.streamingId)
  const formatDateTime = useFormatDateTime()

  /** 时间正序 */
  const sorted = useMemo(
    () =>
      [...messages].sort((a, b) => {
        const ta = new Date(a.createdAt).getTime()
        const tb = new Date(b.createdAt).getTime()
        if (isNaN(ta) || isNaN(tb)) return 0
        return ta - tb
      }),
    [messages],
  )

  /* 空态 */
  if (sorted.length === 0) {
    return (
      <div className="flex flex-1 items-center justify-center p-6">
        <p className="text-sm text-muted-foreground">
          {t("conversation.empty")}
        </p>
      </div>
    )
  }

  return (
    <DwScroll className="flex-1" innerClassName="flex flex-col gap-3 p-[var(--card-spacing)]">
      {sorted.map((m) => (
        <MessageBubble
          key={m.id}
          message={m}
          isStreaming={m.id === streamingId}
          formatDateTime={formatDateTime}
        />
      ))}
    </DwScroll>
  )
}
