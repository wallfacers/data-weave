"use client"

/**
 * Stepper —— 数值步进器（公共 UI 原语）。
 *
 * 用途：在数值范围内 −N+ 调节（血缘展开深度、重试次数、阈值等）。
 * 全仓此前无数值步进原语，故新建并回填 DESIGN.md（reuse-first：目录确无才新建）。
 *
 * 设计约束（DESIGN.md）：语义 token、不手写 dark:、size-* 等宽、hugeicons 图标。
 * 当前值居中可见（FR-004 深度对用户可见）；到边界禁用对应按钮。
 */
import { HugeiconsIcon } from "@hugeicons/react"
import { Add01Icon, MinusSignIcon } from "@hugeicons/core-free-icons"
import { cn } from "@/lib/utils"

export interface StepperProps {
  value: number
  onChange: (value: number) => void
  min: number
  max: number
  step?: number
  className?: string
  ariaLabel?: string
  /** 数值展示宽度（tabular-nums 等宽，默认 "w-6"） */
  valueClassName?: string
  disabled?: boolean
}

export function Stepper({
  value,
  onChange,
  min,
  max,
  step = 1,
  className,
  ariaLabel,
  valueClassName = "w-6",
  disabled,
}: StepperProps) {
  const dec = () => onChange(Math.max(min, value - step))
  const inc = () => onChange(Math.min(max, value + step))
  const atMin = value <= min
  const atMax = value >= max

  return (
    <div
      role="group"
      aria-label={ariaLabel}
      className={cn(
        "inline-flex h-8 items-center rounded-md border border-input bg-background text-xs",
        disabled && "pointer-events-none opacity-50",
        className,
      )}
    >
      <button
        type="button"
        onClick={dec}
        disabled={disabled || atMin}
        aria-label="decrement"
        className="inline-flex size-7 items-center justify-center rounded-l-[5px] text-muted-foreground transition-colors hover:bg-muted hover:text-foreground disabled:opacity-40"
      >
        <HugeiconsIcon icon={MinusSignIcon} className="size-3.5" />
      </button>
      <span className={cn("text-center tabular-nums text-foreground", valueClassName)}>{value}</span>
      <button
        type="button"
        onClick={inc}
        disabled={disabled || atMax}
        aria-label="increment"
        className="inline-flex size-7 items-center justify-center rounded-r-[5px] text-muted-foreground transition-colors hover:bg-muted hover:text-foreground disabled:opacity-40"
      >
        <HugeiconsIcon icon={Add01Icon} className="size-3.5" />
      </button>
    </div>
  )
}
