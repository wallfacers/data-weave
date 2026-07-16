"use client"

/**
 * 070 US3 对话输入框：auto-grow（1→8 行后内滚）、中文输入法组字保护（isComposing 时 Enter 不发送）、
 * 发送/停止状态机（Agent 流式中切停止键→打断本轮）。Agent 回复经 SSE 直播流回显（不在此等待）。
 */
import { useCallback, useEffect, useRef, useState } from "react"
import { useTranslations } from "next-intl"
import { toast } from "sonner"
import { HugeiconsIcon } from "@hugeicons/react"
import { SentIcon, StopIcon } from "@hugeicons/core-free-icons"

import { Button } from "@/components/ui/button"
import { cn } from "@/lib/utils"

const MAX_ROWS = 8
const LINE_HEIGHT_PX = 20

export function ChatComposer({
  onSend,
  onCancel,
  streaming = false,
  disabled,
  placeholder,
}: {
  onSend: (text: string) => Promise<void>
  onCancel?: () => Promise<void>
  streaming?: boolean
  disabled?: boolean
  /** 自定义输入占位符（虚拟管家等复用方按语境覆盖）；缺省用监督席文案。 */
  placeholder?: string
}) {
  const t = useTranslations("supervision")
  const [text, setText] = useState("")
  const [sending, setSending] = useState(false)
  const [cancelling, setCancelling] = useState(false)
  const taRef = useRef<HTMLTextAreaElement | null>(null)

  // auto-grow：重置高度后按 scrollHeight 自适应，封顶 MAX_ROWS 行转内部滚动。
  useEffect(() => {
    const ta = taRef.current
    if (!ta) return
    ta.style.height = "auto"
    ta.style.height = `${Math.min(ta.scrollHeight, MAX_ROWS * LINE_HEIGHT_PX + 16)}px`
  }, [text])

  const submit = useCallback(async () => {
    const trimmed = text.trim()
    if (!trimmed || sending || disabled) return
    setSending(true)
    try {
      await onSend(trimmed)
      setText("") // 成功才清空；失败保留原文
    } catch (e) {
      toast.error(e instanceof Error ? e.message : t("chatSendFailed"))
    } finally {
      setSending(false)
    }
  }, [text, sending, disabled, onSend, t])

  const stop = useCallback(async () => {
    if (!onCancel || cancelling) return
    setCancelling(true)
    try {
      await onCancel()
    } catch (e) {
      // 打断失败/超时：停止键回弹可重试 + 提示。
      toast.error(e instanceof Error ? e.message : t("cancelFailed"))
    } finally {
      setCancelling(false)
    }
  }, [onCancel, cancelling, t])

  const showStop = streaming && !disabled

  return (
    <div
      className={cn(
        "flex items-end gap-2 rounded-[var(--radius)] border border-transparent bg-muted px-3 py-2 transition-[border-color,box-shadow]",
        "focus-within:border-ring/40 focus-within:ring-2 focus-within:ring-ring/30",
        disabled && "opacity-60",
      )}
    >
      <textarea
        ref={taRef}
        value={text}
        onChange={(e) => setText(e.target.value)}
        onKeyDown={(e) => {
          // 中文/日文输入法组字期间的 Enter 用于选词，不触发发送。
          if (e.nativeEvent.isComposing) return
          if (e.key === "Enter" && !e.shiftKey) {
            e.preventDefault()
            void submit()
          }
        }}
        rows={1}
        disabled={disabled || sending}
        aria-label={placeholder ?? t("chatPlaceholder")}
        placeholder={disabled ? t("chatDisabled") : (placeholder ?? t("chatPlaceholder"))}
        className={cn(
          "max-h-40 min-h-9 flex-1 resize-none bg-transparent px-1 py-1.5 text-sm leading-relaxed",
          "outline-none placeholder:text-muted-foreground",
          "disabled:cursor-not-allowed",
        )}
      />
      {showStop ? (
        <Button
          size="icon"
          variant="outline"
          onClick={() => void stop()}
          disabled={cancelling || !onCancel}
          aria-label={t("chatStop")}
          title={t("chatStop")}
        >
          <HugeiconsIcon icon={StopIcon} className={cn(cancelling && "motion-safe:animate-pulse")} />
        </Button>
      ) : (
        <Button
          size="icon"
          onClick={() => void submit()}
          disabled={disabled || sending || !text.trim()}
          aria-label={t("chatSend")}
          title={t("chatSend")}
        >
          <HugeiconsIcon icon={SentIcon} className={cn(sending && "motion-safe:animate-pulse")} />
        </Button>
      )}
    </div>
  )
}
