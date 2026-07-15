"use client"

/**
 * 2D 降级形象 —— CSS 发光能量球 orb。
 * WebGL 不可用时替代 3D 机器人管家。
 * B4 修复：color-mix 语法替代非法 var() / 百分比。
 */
import type { CompanionState } from "@/lib/companion/types"

const STATE_ICON: Record<CompanionState, string> = {
  idle: "●", patrol: "◉", alert: "⚠", think: "◑", speak: "◉",
}

interface OrbFallbackProps { state: CompanionState }

export function OrbFallback({ state }: OrbFallbackProps) {
  return (
    <div className="flex flex-col items-center justify-center gap-4">
      <div
        className="motion-safe:animate-pulse rounded-full"
        style={{
          width: 120, height: 120,
          background: `radial-gradient(circle at 35% 35%, color-mix(in oklab, var(--companion-${state}) 90%, transparent), color-mix(in oklab, var(--companion-${state}) 15%, transparent) 60%, transparent)`,
          boxShadow: `0 0 40px var(--companion-${state})`,
        }}
        role="img"
        aria-label={`Companion state: ${state}`}
      />
      <span className="text-4xl" style={{ color: `var(--companion-${state})` }} aria-hidden="true">
        {STATE_ICON[state]}
      </span>
    </div>
  )
}
