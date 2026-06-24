/** 聊天输入框：Enter 发送 / Shift+Enter 换行，流式中显示停止按钮。 */
"use client"

import { useCallback, useEffect, useRef, useState } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import { ArrowUp01Icon, StopIcon } from "@hugeicons/core-free-icons"

import { useChatStore } from "@/lib/chat/store"
import type { AgentPageContext } from "@/lib/chat/types"

export function ChatInput({ context }: { context?: AgentPageContext }) {
  const t = useTranslations("chat")
  const send = useChatStore((s) => s.sendMessage)
  const cancel = useChatStore((s) => s.cancel)
  const activeId = useChatStore((s) => s.activeId)
  const isStreaming = useChatStore((s) =>
    activeId ? (s.runtimes[activeId]?.streaming.size ?? 0) > 0 : false,
  )
  const [text, setText] = useState("")
  const taRef = useRef<HTMLTextAreaElement>(null)

  // 自适应高度（上限 160px）
  useEffect(() => {
    const ta = taRef.current
    if (!ta) return
    ta.style.height = "auto"
    ta.style.height = `${Math.min(ta.scrollHeight, 160)}px`
  }, [text])

  const submit = useCallback(() => {
    const v = text.trim()
    if (!v || isStreaming) return
    setText("")
    void send(v, context)
  }, [text, isStreaming, send, context])

  return (
    <div className="shrink-0 border-t p-3">
      <div className="flex items-end gap-2 rounded-[var(--radius-lg)] border bg-card px-3 py-2 focus-within:ring-1 focus-within:ring-ring">
        <textarea
          ref={taRef}
          value={text}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && !e.shiftKey) {
              e.preventDefault()
              submit()
            }
          }}
          placeholder={t("placeholder")}
          rows={1}
          className="max-h-40 flex-1 resize-none bg-transparent py-1 text-sm outline-none placeholder:text-muted-foreground"
        />
        {isStreaming ? (
          <button
            type="button"
            onClick={cancel}
            aria-label={t("stop")}
            className="flex size-8 shrink-0 items-center justify-center rounded-lg text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
          >
            <HugeiconsIcon icon={StopIcon} className="size-4" />
          </button>
        ) : (
          <button
            type="button"
            onClick={submit}
            disabled={!text.trim()}
            aria-label={t("send")}
            className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-primary text-primary-foreground transition-opacity disabled:opacity-40"
          >
            <HugeiconsIcon icon={ArrowUp01Icon} className="size-4" />
          </button>
        )}
      </div>
    </div>
  )
}
