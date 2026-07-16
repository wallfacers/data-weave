"use client"

/**
 * 虚拟管家视图总装。
 *
 * 布局：全出血 3D canvas 底层 + UI overlay（顶部概况 → 字幕 → 右侧卡片栈 → 底部输入框）
 */
import { useState, useCallback, useRef, useEffect, useMemo } from "react"
import dynamic from "next/dynamic"
import { useTranslations } from "next-intl"
import { toast } from "sonner"
import { HugeiconsIcon } from "@hugeicons/react"
import { AiMicIcon } from "@hugeicons/core-free-icons"
import { Button } from "@/components/ui/button"
import { ViewContainer } from "@/components/ui/view-container"
import { LoadingState } from "@/components/workspace/shared/loading-state"
import { ChatComposer } from "@/components/workspace/shared/chat-composer"
import { useFormatDateTime } from "@/hooks/use-format-date-time"
import { useCompanionStream } from "@/lib/companion/use-companion-stream"
import { useCompanionStore } from "@/lib/companion/store"
import { useWorkspaceStore } from "@/lib/workspace/store"
import { sendChat, cancelChat } from "@/lib/companion/api"
import { OrbFallback } from "./companion/orb-fallback"
import { ReportStack } from "./companion/report-stack"
import { SpeechBubble } from "./companion/speech-bubble"

const CompanionStage = dynamic(
  () => import("./companion/companion-stage"),
  { ssr: false, loading: () => <LoadingState /> }
)

export function CompanionView() {
  const t = useTranslations("companion")
  const tc = useTranslations("common")
  const formatDateTime = useFormatDateTime()
  const state = useCompanionStore((s) => s.state)
  const briefing = useCompanionStore((s) => s.briefing)
  const connection = useCompanionStore((s) => s.connection)
  const streamingId = useCompanionStore((s) => s.streamingId)
  const allMessages = useCompanionStore((s) => s.messages)

  const [webglFailed, setWebglFailed] = useState(false)
  const [speechText, setSpeechText] = useState<string | null>(null)
  const [streaming, setStreaming] = useState(false)
  const openView = useWorkspaceStore((s) => s.open)
  const speechTimerRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)

  const { reconnect } = useCompanionStream()

  const handleWebGLUnavailable = useCallback(() => setWebglFailed(true), [])

  /* 字幕播报 */
  const speak = useCallback((text: string) => {
    setSpeechText(text)
    clearTimeout(speechTimerRef.current)
    speechTimerRef.current = setTimeout(() => setSpeechText(null), Math.max(2400, text.length * 130))
  }, [])

  /* 管家最新一条(流式中的)回复 → 字幕实时播报;流结束后按长度停留再隐藏。
     SYSTEM 也纳入——大脑失败的兜底报错必须让用户看见,不能静默(否则「零反应」)。 */
  const latestAgentReply = useMemo(() => {
    for (let i = allMessages.length - 1; i >= 0; i--) {
      const m = allMessages[i]
      if ((m.role === "AGENT" || m.role === "SYSTEM") && !m.reportId && m.content) return m
    }
    return null
  }, [allMessages])
  useEffect(() => {
    if (latestAgentReply?.content) speak(latestAgentReply.content)
    // speak 自带隐藏 timer;依赖 content 使流式增量持续刷新字幕
  }, [latestAgentReply?.id, latestAgentReply?.content, speak])

  /* 发送消息 */
  const handleSend = useCallback(async (text: string) => {
    setStreaming(true)
    try {
      await sendChat({ content: text })
    } catch (e) {
      const msg = e instanceof Error ? e.message : t("chat.sendFailed")
      toast.error(msg)
    } finally {
      setStreaming(false)
    }
  }, [t])

  /* 停止 */
  const handleCancel = useCallback(async () => {
    try { await cancelChat() } catch (e) {
      const msg = e instanceof Error ? e.message : t("chat.sendFailed")
      toast.error(msg)
    }
    setStreaming(false)
  }, [t])

  // 清理字幕 timer
  const speechTimerCleanup = useCallback(() => {
    clearTimeout(speechTimerRef.current)
    setSpeechText(null)
  }, [])

  const stateLine = t(`state.${state}`)

  return (
    <ViewContainer className="!p-0 relative overflow-hidden">
      {/* 3D 场景 */}
      <div className="absolute inset-0 z-0">
        {webglFailed ? (
          <div className="flex h-full items-center justify-center">
            <OrbFallback state={state} />
          </div>
        ) : (
          <CompanionStage onWebGLUnavailable={handleWebGLUnavailable} />
        )}
      </div>

      {/* 氛围层 — B4 修复：color-mix 语法 */}
      <div className="pointer-events-none absolute inset-0 z-[1]">
        <div
          className="absolute inset-0"
          style={{
            background: `
              radial-gradient(ellipse 55% 45% at 42% 58%, color-mix(in oklab, var(--companion-${state}) 7%, transparent) 0%, transparent 70%),
              radial-gradient(ellipse 70% 60% at 50% 110%, color-mix(in oklab, var(--companion-${state}) 12%, transparent) 0%, transparent 70%)
            `,
          }}
        />
        <div
          className="absolute bottom-0 left-0 right-0 h-[34%]"
          style={{
            backgroundImage: `
              linear-gradient(color-mix(in oklab, var(--companion-${state}) 5%, transparent) 1px, transparent 1px),
              linear-gradient(90deg, color-mix(in oklab, var(--companion-${state}) 5%, transparent) 1px, transparent 1px)
            `,
            backgroundSize: "44px 44px",
            transform: "perspective(420px) rotateX(58deg)",
            transformOrigin: "bottom",
            maskImage: "linear-gradient(to top, rgba(0,0,0,.8), transparent)",
            WebkitMaskImage: "linear-gradient(to top, rgba(0,0,0,.8), transparent)",
          }}
        />
      </div>

      {/* UI overlay */}
      <div className="pointer-events-none absolute inset-0 z-[2]">
        {/* 顶部状态条 */}
        <div className="pointer-events-auto flex items-center gap-4 px-5 py-3.5">
          <div className="flex items-center gap-2.5">
            <div
              className="size-[34px] shrink-0 rounded-full motion-safe:animate-pulse"
              style={{
                background: `radial-gradient(circle at 35% 35%, color-mix(in oklab, var(--companion-${state}) 90%, transparent), color-mix(in oklab, var(--companion-${state}) 15%, transparent) 60%, transparent)`,
                boxShadow: `0 0 18px var(--companion-${state})`,
              }}
            />
            <div>
              <div className="text-base font-semibold tracking-wide">
                {t("name")}
                <span className="text-xs text-muted-foreground"> · {t("role")}</span>
              </div>
              <div className="text-xs text-muted-foreground">{stateLine}</div>
            </div>
          </div>

          <div className="flex-1" />

          {connection === "live" && (
            <div className="pointer-events-auto flex items-center gap-2 text-xs text-muted-foreground">
              <span className="inline-flex items-center gap-1.5 rounded-full border border-border/50 bg-card/70 px-2.5 py-1 backdrop-blur-sm">
                <span className="size-[7px] rounded-full motion-safe:animate-pulse"
                  style={{ backgroundColor: `var(--companion-${state})` }} />
                {t("briefing.todayRuns", { count: briefing.todayRuns })}
              </span>
              {briefing.openAnomalies > 0 && (
                <span className="inline-flex items-center gap-1.5 rounded-full border border-border/50 bg-card/70 px-2.5 py-1 backdrop-blur-sm">
                  <span className="size-[7px] rounded-full bg-warning" />
                  {t("briefing.openAnomalies", { count: briefing.openAnomalies })}
                </span>
              )}
              {briefing.nextPatrolAt && (
                <span className="inline-flex items-center gap-1.5 rounded-full border border-border/50 bg-card/70 px-2.5 py-1 backdrop-blur-sm">
                  {t("briefing.nextPatrol", { time: formatDateTime(briefing.nextPatrolAt) })}
                </span>
              )}
            </div>
          )}
          {connection === "connecting" && <span className="text-xs text-muted-foreground">{t("connect.connecting")}</span>}
          {connection === "disconnected" && (
            <span className="pointer-events-auto flex items-center gap-2 text-xs text-muted-foreground">
              {t("connect.disconnected")}
              <Button size="sm" variant="outline" onClick={reconnect}>{tc("retry")}</Button>
            </span>
          )}

          <Button
            size="sm" variant="outline"
            className="pointer-events-auto"
            onClick={() => openView("companion-routine")}
          >
            {t("routine.title")}
          </Button>
        </div>

        {/* 中央字幕 */}
        <div className="pointer-events-none absolute left-1/2 top-[26%] -translate-x-1/2">
          <SpeechBubble text={speechText} />
        </div>

        {/* 右侧汇报卡片栈 */}
        <div className="pointer-events-auto absolute right-4 top-16 bottom-24 w-[372px]">
          <ReportStack />
        </div>

        {/* 底部全局输入框 — 复用 ChatComposer（auto-grow/IME 组字保护/发送-停止状态机） */}
        <div className="pointer-events-auto absolute bottom-5 left-1/2 w-[min(680px,calc(100vw-440px))] -translate-x-1/2">
          <div className="flex items-end gap-2">
            <div className="flex-1">
              <ChatComposer
                onSend={handleSend}
                onCancel={handleCancel}
                streaming={streaming || streamingId != null}
                placeholder={t("chat.placeholder")}
              />
            </div>
            {/* 语音输入占位 T027 */}
            <Button
              size="icon" variant="ghost"
              disabled
              title={t("chat.voiceDisabled")}
            >
              <HugeiconsIcon icon={AiMicIcon} className="size-4" />
            </Button>
          </div>
        </div>
      </div>
    </ViewContainer>
  )
}
