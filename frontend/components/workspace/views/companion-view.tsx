"use client"

/**
 * 虚拟管家视图总装。
 *
 * 布局：全出血 3D canvas 底层 + UI overlay（顶部概况 → 字幕 → 右侧卡片栈 → 底部输入框）
 * 状态驱动：形象形态 / 概况数据 / 连接状态 均来自 SSE store
 */
import { useState, useCallback, useRef } from "react"
import dynamic from "next/dynamic"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import { AiMicIcon } from "@hugeicons/core-free-icons"
import { ViewContainer } from "@/components/ui/view-container"
import { LoadingState } from "@/components/workspace/shared/loading-state"
import { useCompanionStream } from "@/lib/companion/use-companion-stream"
import { useCompanionStore } from "@/lib/companion/store"
import { sendChat, cancelChat } from "@/lib/companion/api"
import { OrbFallback } from "./companion/orb-fallback"
import { ReportStack } from "./companion/report-stack"
import { SpeechBubble } from "./companion/speech-bubble"
import { RoutinePanel } from "./companion/routine-panel"

const CompanionStage = dynamic(
  () => import("./companion/companion-stage"),
  { ssr: false, loading: () => <LoadingState /> }
)

export function CompanionView() {
  const t = useTranslations("companion")
  const state = useCompanionStore((s) => s.state)
  const briefing = useCompanionStore((s) => s.briefing)
  const connection = useCompanionStore((s) => s.connection)

  const [webglFailed, setWebglFailed] = useState(false)
  const [speechText, setSpeechText] = useState<string | null>(null)
  const [inputValue, setInputValue] = useState("")
  const [sending, setSending] = useState(false)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)
  const speechTimerRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)

  useCompanionStream()

  const handleWebGLUnavailable = useCallback(() => setWebglFailed(true), [])

  const speak = useCallback((text: string) => {
    setSpeechText(text)
    clearTimeout(speechTimerRef.current)
    speechTimerRef.current = setTimeout(() => setSpeechText(null), Math.max(2400, text.length * 130))
  }, [])

  const handleSend = useCallback(async () => {
    const v = inputValue.trim()
    if (!v || sending) return
    setInputValue("")
    setSending(true)
    try {
      await sendChat({ content: v })
    } catch {
      speak("管家大脑暂不可用，请稍后重试")
    } finally {
      setSending(false)
    }
  }, [inputValue, sending, speak])

  const handleStop = useCallback(async () => {
    try {
      await cancelChat()
    } catch {
      // silent
    }
    setSending(false)
  }, [])

  const stateLine = t(`state.${state}`)

  return (
    <ViewContainer className="!p-0 relative overflow-hidden">
      {/* 3D 场景底层 */}
      <div className="absolute inset-0 z-0">
        {webglFailed ? (
          <div className="flex h-full items-center justify-center">
            <OrbFallback state={state} />
          </div>
        ) : (
          <CompanionStage onWebGLUnavailable={handleWebGLUnavailable} />
        )}
      </div>

      {/* 氛围层 */}
      <div className="pointer-events-none absolute inset-0 z-[1]">
        <div
          className="absolute inset-0"
          style={{
            background: `
              radial-gradient(ellipse 55% 45% at 42% 58%, var(--companion-${state}) / 7%, transparent 70%),
              radial-gradient(ellipse 70% 60% at 50% 110%, var(--companion-${state}) / 12%, transparent 70%)
            `,
          }}
        />
        <div
          className="absolute bottom-0 left-0 right-0 h-[34%]"
          style={{
            backgroundImage: `
              linear-gradient(var(--companion-${state}) / 5% 1px, transparent 1px),
              linear-gradient(90deg, var(--companion-${state}) / 5% 1px, transparent 1px)
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
                background: `radial-gradient(circle at 35% 35%, var(--companion-${state}) / 90%, var(--companion-${state}) / 15% 60%, transparent)`,
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

          {/* 概况 pills */}
          {connection === "live" && (
            <div className="pointer-events-auto flex items-center gap-2 text-xs text-muted-foreground">
              <span className="inline-flex items-center gap-1.5 rounded-full border border-border/50 bg-card/70 px-2.5 py-1 backdrop-blur-sm">
                <span
                  className="size-[7px] rounded-full motion-safe:animate-pulse"
                  style={{ backgroundColor: `var(--companion-${state})` }}
                />
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
                  {t("briefing.nextPatrol", { time: briefing.nextPatrolAt })}
                </span>
              )}
            </div>
          )}
          {connection === "connecting" && (
            <span className="text-xs text-muted-foreground">{t("connect.connecting")}</span>
          )}
          {connection === "disconnected" && (
            <span className="text-xs text-muted-foreground">{t("connect.disconnected")}</span>
          )}

          {/* 汇报/设置按钮 */}
          <button
            className="pointer-events-auto rounded-lg border border-border/50 bg-card/70 px-3.5 py-1.5 text-[13px] text-foreground backdrop-blur-sm hover:border-primary/50"
            onClick={() => setSettingsOpen(!settingsOpen)}
          >
            {t("routine.title")}
          </button>
        </div>

        {/* 中央字幕 */}
        <div className="pointer-events-none absolute left-1/2 top-[26%] -translate-x-1/2">
          <SpeechBubble text={speechText} />
        </div>

        {/* 右侧汇报卡片栈 */}
        <div className="pointer-events-auto absolute right-4 top-16 bottom-24 w-[372px]">
          <ReportStack />
        </div>

        {/* 底部全局输入框 */}
        <div className="pointer-events-auto absolute bottom-5 left-1/2 w-[min(680px,calc(100vw-440px))] -translate-x-1/2">
          <div className="flex items-center gap-2.5 rounded-2xl border border-border/50 bg-card/80 px-3 py-2.5 backdrop-blur-md shadow-[0_12px_40px_rgba(0,0,0,.2)] focus-within:border-primary/50 focus-within:shadow-[0_12px_40px_rgba(0,0,0,.2),0_0_0_1px_var(--primary)]">
            <input
              ref={inputRef}
              className="min-w-0 flex-1 bg-transparent text-sm text-foreground outline-none placeholder:text-muted-foreground/55"
              placeholder={t("chat.placeholder")}
              value={inputValue}
              onChange={(e) => setInputValue(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter" && !e.nativeEvent.isComposing) handleSend()
              }}
              disabled={sending}
            />
            {/* 语音输入占位（T027） */}
            <button
              className="flex size-9 items-center justify-center rounded-[10px] text-muted-foreground/35"
              disabled
              title={t("chat.voiceDisabled")}
            >
              <HugeiconsIcon icon={AiMicIcon} className="size-4" />
            </button>
            {sending ? (
              <button
                className="flex size-9 items-center justify-center rounded-[10px] bg-destructive/10 text-destructive hover:bg-destructive/20"
                onClick={handleStop}
                title={t("chat.stop")}
              >
                ■
              </button>
            ) : (
              <button
                className="flex size-9 items-center justify-center rounded-[10px] bg-primary/10 text-primary hover:bg-primary/20"
                onClick={handleSend}
                disabled={!inputValue.trim()}
                title={t("chat.send")}
              >
                ➤
              </button>
            )}
          </div>
        </div>
      </div>

      {/* 巡检设置抽屉 */}
      <RoutinePanel open={settingsOpen} onClose={() => setSettingsOpen(false)} />
    </ViewContainer>
  )
}
