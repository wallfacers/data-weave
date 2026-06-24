/**
 * 聊天输入框（自研 composer，对齐 workhorse-assistant 观感）：
 * 上 textarea（自适应高度 ≤200px）+ 下工具栏行（实时通道状态点 / 可开关上下文胶囊 / 发送·停止）。
 * - IME 守卫：中文等输入法选字态按 Enter 不误发送（workhorse 同款 isComposing 拦截）。
 * - 上下文胶囊：基于真实 AgentPageContext，点 × 可不附带、点「附带」可恢复，发送时透传 forwardedProps。
 */
"use client"

import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  Add01Icon,
  ArrowUp01Icon,
  Cancel01Icon,
  CpuIcon,
  Layers01Icon,
  StopIcon,
  TaskDaily01Icon,
} from "@hugeicons/core-free-icons"

import { cn } from "@/lib/utils"
import { useChatStore } from "@/lib/chat/store"
import type { AgentPageContext } from "@/lib/chat/types"

function hasContext(c?: AgentPageContext): c is AgentPageContext {
  return !!c && !!(c.instanceId || c.nodeId || c.taskId || c.module || c.pathname)
}

/** ID 截断（… 表示裁剪内容，符合规范：仅 ID 截断允许省略号）。 */
function shortId(s: string): string {
  return s.length > 10 ? `${s.slice(0, 8)}…` : s
}

export function ChatInput({ context }: { context?: AgentPageContext }) {
  const t = useTranslations("chat")
  const send = useChatStore((s) => s.sendMessage)
  const cancel = useChatStore((s) => s.cancel)
  const activeId = useChatStore((s) => s.activeId)
  const streamConnected = useChatStore((s) => s.streamConnected)
  const isStreaming = useChatStore((s) =>
    activeId ? (s.runtimes[activeId]?.streaming.size ?? 0) > 0 : false,
  )

  const ctx = hasContext(context) ? context : undefined
  const [text, setText] = useState("")
  const [attach, setAttach] = useState(true)
  const taRef = useRef<HTMLTextAreaElement>(null)

  // 上下文切换（切视图/实例）时恢复默认「附带」。
  useEffect(() => {
    setAttach(true)
  }, [context?.instanceId, context?.nodeId, context?.taskId, context?.module, context?.pathname])

  // 自适应高度（上限 200px）。
  useEffect(() => {
    const ta = taRef.current
    if (!ta) return
    ta.style.height = "auto"
    ta.style.height = `${Math.min(ta.scrollHeight, 200)}px`
  }, [text])

  const chip = useMemo(() => {
    if (!ctx) return null
    if (ctx.instanceId) return { icon: TaskDaily01Icon, label: t("ctxInstance", { id: shortId(ctx.instanceId) }) }
    if (ctx.taskId) return { icon: TaskDaily01Icon, label: t("ctxTask", { id: shortId(ctx.taskId) }) }
    if (ctx.nodeId) return { icon: CpuIcon, label: t("ctxNode", { id: ctx.nodeId }) }
    return { icon: Layers01Icon, label: ctx.module ?? t("ctxView") }
  }, [ctx, t])

  const submit = useCallback(() => {
    const v = text.trim()
    if (!v || isStreaming) return
    setText("")
    void send(v, attach ? ctx : undefined)
  }, [text, isStreaming, send, attach, ctx])

  return (
    <div className="shrink-0 border-t p-3">
      <div className="flex flex-col gap-2 rounded-xl border bg-card px-3 py-2.5 focus-within:ring-1 focus-within:ring-ring">
        <textarea
          ref={taRef}
          value={text}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => {
            if (e.nativeEvent.isComposing) return // 输入法选字态不发送（CJK）
            if (e.key === "Enter" && !e.shiftKey) {
              e.preventDefault()
              submit()
            }
          }}
          placeholder={t("placeholder")}
          rows={1}
          className="max-h-[200px] min-h-[1.5rem] w-full resize-none bg-transparent text-sm leading-relaxed outline-none placeholder:text-muted-foreground [scrollbar-width:none] [&::-webkit-scrollbar]:hidden"
        />

        {/* 工具栏行 */}
        <div className="flex items-center justify-between gap-2">
          <div className="flex min-w-0 items-center gap-2">
            {/* 实时通道（主动开口）状态点 */}
            <span
              title={streamConnected ? t("streamLive") : t("streamOffline")}
              className={cn(
                "size-1.5 shrink-0 rounded-full",
                streamConnected ? "animate-pulse bg-success" : "bg-muted-foreground/40",
              )}
            />
            {/* 上下文胶囊：附带态可移除、移除后可恢复 */}
            {chip && attach ? (
              <span className="flex min-w-0 items-center gap-1 rounded-full border bg-muted/60 py-0.5 pr-1 pl-2 text-xs text-muted-foreground">
                <HugeiconsIcon icon={chip.icon} className="size-3 shrink-0" />
                <span className="truncate">{chip.label}</span>
                <button
                  type="button"
                  onClick={() => setAttach(false)}
                  aria-label={t("detachContext")}
                  className="flex size-4 shrink-0 items-center justify-center rounded-full hover:bg-background hover:text-foreground"
                >
                  <HugeiconsIcon icon={Cancel01Icon} className="size-3" />
                </button>
              </span>
            ) : chip ? (
              <button
                type="button"
                onClick={() => setAttach(true)}
                aria-label={t("attachContext")}
                className="flex items-center gap-1 rounded-full border border-dashed px-2 py-0.5 text-xs text-muted-foreground transition-colors hover:bg-muted/50 hover:text-foreground"
              >
                <HugeiconsIcon icon={Add01Icon} className="size-3" />
                <span>{t("attachContext")}</span>
              </button>
            ) : null}
          </div>

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
    </div>
  )
}
