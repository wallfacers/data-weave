"use client"

/**
 * Segmented —— 二元/小枚举段控（公共 UI 原语）。
 *
 * 用途：在少量互斥选项间切换（方向 上/下/双向、粒度 表/列、状态分段等）。
 * 复用沉淀自 data-table-toolbar 的私有 SegmentedControl，提升为公共原语（reuse-first）。
 *
 * 设计约束（DESIGN.md）：语义 token、不手写 dark:、gap-* 间距、hugeicons 图标。
 * 选中态用 bg-muted（中性灰，与黑白主题一致），非 bg-primary（段控语义为「切换」非「主操作」）。
 */
import { HugeiconsIcon, type IconSvgElement } from "@hugeicons/react"
import { cn } from "@/lib/utils"

export interface SegmentedOption {
  value: string
  label: string
  icon?: IconSvgElement
}

export interface SegmentedProps {
  options: SegmentedOption[]
  value: string
  onChange: (value: string) => void
  /** 尺寸：sm（h-8，工具条紧凑）/ md（h-9，表单对齐）。默认 sm。 */
  size?: "sm" | "md"
  className?: string
  /** 透传给内部按钮组容器的 aria-label。 */
  ariaLabel?: string
  disabled?: boolean
}

const SIZE_CLS: Record<NonNullable<SegmentedProps["size"]>, string> = {
  sm: "h-8 text-xs",
  md: "h-9 text-sm",
}

export function Segmented({
  options,
  value,
  onChange,
  size = "sm",
  className,
  ariaLabel,
  disabled,
}: SegmentedProps) {
  return (
    <div
      role="group"
      aria-label={ariaLabel}
      className={cn(
        "inline-flex items-center rounded-md border border-input bg-background p-0.5",
        disabled && "pointer-events-none opacity-50",
        SIZE_CLS[size],
        className,
      )}
    >
      {options.map((o) => {
        const active = o.value === value
        return (
          <button
            key={o.value}
            type="button"
            aria-pressed={active}
            disabled={disabled}
            onClick={() => onChange(o.value)}
            className={cn(
              "inline-flex h-full items-center gap-1 rounded px-2 transition-colors",
              active
                ? "bg-muted font-medium text-foreground"
                : "text-muted-foreground hover:text-foreground",
            )}
          >
            {o.icon && <HugeiconsIcon icon={o.icon} className="size-3.5 shrink-0" />}
            <span className="truncate whitespace-nowrap">{o.label}</span>
          </button>
        )
      })}
    </div>
  )
}
