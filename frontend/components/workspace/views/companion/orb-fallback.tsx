"use client"

/**
 * 2D 降级形象 —— CSS 发光能量球 orb。
 * WebGL 不可用时替代 3D 机器人管家；
 * 复用同一状态机换色/脉动，卡片/概况功能不受影响。
 *
 * prefers-reduced-motion 时停用脉动动画，保留静态状态色。
 */
import type { CompanionState } from "@/lib/companion/types"

const STATE_LABEL: Record<CompanionState, string> = {
  idle: "●",
  patrol: "◉",
  alert: "⚠",
  think: "◑",
  speak: "◉",
}

interface OrbFallbackProps {
  state: CompanionState
}

export function OrbFallback({ state }: OrbFallbackProps) {
  return (
    <div className="flex flex-col items-center justify-center gap-4">
      {/* 发光能量球 */}
      <div
        className="motion-safe:animate-pulse rounded-full"
        style={{
          width: 120,
          height: 120,
          background: `radial-gradient(circle at 35% 35%, var(--companion-${state}), var(--companion-${state}) / 15% 60%, transparent)`,
          boxShadow: `0 0 40px var(--companion-${state})`,
        }}
        role="img"
        aria-label={`Companion state: ${state}`}
      />
      {/* 状态标识文字 */}
      <span
        className="text-4xl"
        style={{ color: `var(--companion-${state})` }}
        aria-hidden="true"
      >
        {STATE_LABEL[state]}
      </span>
    </div>
  )
}
