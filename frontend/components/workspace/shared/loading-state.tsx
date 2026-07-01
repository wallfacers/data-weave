"use client"

import { useEffect, useRef, useState } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import { RefreshIcon } from "@hugeicons/core-free-icons"
import { cn } from "@/lib/utils"

export interface LoadingStateProps {
  /**
   * 加载状态是否活跃。默认 true（组件挂载即显示）。
   * 当 active 变为 false 时，旋转动画会兜底显示至少 minSpinMs 毫秒再消失，
   * 避免快速加载时的视觉闪烁。
   *
   * 用法：<LoadingState active={loading} />  —— 组件常驻，通过 active 控制显隐
   *       {loading && <LoadingState />}        —— 也支持条件渲染，但无最小显示兜底
   */
  active?: boolean
  /** 加载文字，默认通过 i18n 获取（fallback: "Loading…"） */
  text?: string
  /** 显示模式：centered（全区域居中，默认）| overlay（绝对定位半透明遮罩） */
  variant?: "centered" | "overlay"
  /** 图标尺寸（默认 size-5） */
  iconSize?: string
  /** 文字样式（默认 text-xs） */
  textSize?: string
}

/**
 * 最小旋转时间 hook —— 与 hooks/use-min-spin.ts 模式相同，
 * 区别在于初始化时 visible 跟随 active 初值，确保首次挂载即显示。
 */
function useLoadingMinSpin(active: boolean, minMs = 1000): boolean {
  const [visible, setVisible] = useState(active)
  const [prevActive, setPrevActive] = useState(active)
  const startRef = useRef(0)

  // 上升沿点亮
  if (active !== prevActive) {
    setPrevActive(active)
    if (active) setVisible(true)
  }

  // 点亮时记录起点
  useEffect(() => {
    if (visible && startRef.current === 0) startRef.current = Date.now()
  }, [visible])

  // active 结束且仍在显示：续到满 minMs 再关
  useEffect(() => {
    if (active || !visible) return
    const remaining = Math.max(0, minMs - (Date.now() - startRef.current))
    const timer = setTimeout(() => {
      setVisible(false)
      startRef.current = 0
    }, remaining)
    return () => clearTimeout(timer)
  }, [active, visible, minMs])

  return visible
}

/**
 * 统一的加载状态组件 —— 旋转图标 + 文字，居中显示。
 *
 * 支持两种模式：
 * - centered（默认）：flex 居中占位，用于无数据时的全区域加载
 * - overlay：绝对定位半透明遮罩，用于有旧数据时的后台刷新覆盖层
 *
 * 无障碍：图标仅在用户未开启"减少动画"时旋转（motion-safe:animate-spin）；
 * 提供 role="status" + aria-label 供屏幕阅读器识别加载状态。
 */
export function LoadingState({
  active = true,
  text,
  variant = "centered",
  iconSize = "size-5",
  textSize = "text-xs",
}: LoadingStateProps) {
  const t = useTranslations("common")
  const visible = useLoadingMinSpin(active)

  if (!visible) return null

  const label = text ?? t("loading")
  const spinner = (
    <div
      className="flex flex-col items-center justify-center gap-2 text-muted-foreground"
      role="status"
      aria-label={label}
    >
      <HugeiconsIcon
        icon={RefreshIcon}
        className={cn(iconSize, "motion-safe:animate-spin")}
      />
      <span className={textSize}>{label}</span>
    </div>
  )

  if (variant === "overlay") {
    return (
      <div className="absolute inset-0 z-10 flex items-center justify-center bg-card/60">
        {spinner}
      </div>
    )
  }

  return (
    <div className="flex flex-1 items-center justify-center py-12">
      {spinner}
    </div>
  )
}
