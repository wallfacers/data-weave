"use client"

/**
 * 虚拟管家视图总装（US1-US4 渐进填充）。
 *
 * 布局：全出血 3D canvas 底层 + UI overlay 层：
 *   顶部概况 → 中央字幕气泡 → 右侧汇报卡片栈 → 底部对话输入框
 *
 * US1 完成度：形象 + 概况 + 字幕 + 连接态
 */
import { useState, useCallback } from "react"
import dynamic from "next/dynamic"
import { useTranslations } from "next-intl"
import { ViewContainer } from "@/components/ui/view-container"
import { LoadingState } from "@/components/workspace/shared/loading-state"
import { useCompanionStream } from "@/lib/companion/use-companion-stream"
import { useCompanionStore } from "@/lib/companion/store"
import type { CompanionState } from "@/lib/companion/types"
import { OrbFallback } from "./companion/orb-fallback"

/* three.js 场景动态导入，SSR 关闭 */
const CompanionStage = dynamic(
  () => import("./companion/companion-stage"),
  { ssr: false, loading: () => <LoadingState /> }
)

export function CompanionView() {
  const t = useTranslations("companion")
  const store = useCompanionStore
  const state = store((s) => s.state)
  const briefing = store((s) => s.briefing)
  const connection = store((s) => s.connection)

  const [webglFailed, setWebglFailed] = useState(false)

  // 启动 SSE 流
  useCompanionStream()

  const handleWebGLUnavailable = useCallback(() => {
    setWebglFailed(true)
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

      {/* 氛围层（网格地板 + 径向光晕） */}
      <div className="pointer-events-none absolute inset-0 z-1">
        {/* 径向光晕 */}
        <div
          className="absolute inset-0"
          style={{
            background: `
              radial-gradient(ellipse 55% 45% at 42% 58%, var(--companion-${state}) / 7%, transparent 70%),
              radial-gradient(ellipse 70% 60% at 50% 110%, var(--companion-${state}) / 12%, transparent 70%)
            `,
          }}
        />
        {/* 网格地板 */}
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

      {/* UI overlay 层 */}
      <div className="pointer-events-none absolute inset-0 z-2">
        {/* 顶部状态条 */}
        <div className="pointer-events-auto flex items-center gap-4 px-5 py-3.5">
          {/* 管家身份 */}
          <div className="flex items-center gap-2.5">
            <div
              className="size-[34px] rounded-full motion-safe:animate-pulse"
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
          <div className="flex items-center gap-2 text-xs text-muted-foreground">
            {connection === "live" && (
              <>
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
              </>
            )}
            {connection === "connecting" && (
              <span className="text-muted-foreground">{t("connect.connecting")}</span>
            )}
            {connection === "disconnected" && (
              <span className="text-muted-foreground">{t("connect.disconnected")}</span>
            )}
          </div>
        </div>
      </div>
    </ViewContainer>
  )
}
