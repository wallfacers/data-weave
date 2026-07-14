"use client"

/**
 * T037 对话输入框：Enter 发送（Shift+Enter 换行），发送态禁用。Agent 回复经 SSE 直播流回显（不在此组件等待）。
 */
import { useState } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import { SentIcon } from "@hugeicons/core-free-icons"

import { Button } from "@/components/ui/button"
import { cn } from "@/lib/utils"

export function ChatComposer({
  onSend,
  disabled,
}: {
  onSend: (text: string) => Promise<void>
  disabled?: boolean
}) {
  const t = useTranslations("supervision")
  const [text, setText] = useState("")
  const [sending, setSending] = useState(false)

  const submit = async () => {
    const trimmed = text.trim()
    if (!trimmed || sending || disabled) return
    setSending(true)
    try {
      await onSend(trimmed)
      setText("")
    } finally {
      setSending(false)
    }
  }

  return (
    <div className="flex items-end gap-2">
      <textarea
        value={text}
        onChange={(e) => setText(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Enter" && !e.shiftKey) {
            e.preventDefault()
            void submit()
          }
        }}
        rows={1}
        disabled={disabled || sending}
        placeholder={disabled ? t("chatDisabled") : t("chatPlaceholder")}
        className={cn(
          "min-h-9 max-h-32 flex-1 resize-none rounded-[var(--radius)] bg-muted px-3 py-2 text-sm",
          "outline-none placeholder:text-muted-foreground focus-visible:ring-2 focus-visible:ring-ring/40",
          "disabled:opacity-60",
        )}
      />
      <Button
        size="icon"
        onClick={() => void submit()}
        disabled={disabled || sending || !text.trim()}
        title={t("chatSend")}
      >
        <HugeiconsIcon icon={SentIcon} className={cn(sending && "motion-safe:animate-pulse")} />
      </Button>
    </div>
  )
}
