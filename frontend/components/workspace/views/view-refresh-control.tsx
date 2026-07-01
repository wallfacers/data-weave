"use client"

/**
 * 统一手动刷新控件（ViewRefreshControl）。
 *
 * 设计约束（采纳自 DESIGN.md）：
 * - 图标：hugeicons RefreshIcon，size-4，ghost 按钮。
 * - 刷新中用图标旋转（animate-spin）+ disabled，不用 `…` 表示进行中。
 * - 语义 token：text-muted-foreground / hover:bg-accent。
 * - 开关：US1 期不渲染（onToggleAuto 未传），US3 激活。
 * - 位置：卡片型视图放 header 右侧、表格型放 DataTableToolbar 右侧。
 * - 三段式布局不破坏：本控件不引入额外滚动区或 border。
 */

import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import { RefreshIcon } from "@hugeicons/core-free-icons"
import { cn } from "@/lib/utils"
import { useMinSpin } from "@/hooks/use-min-spin"

export interface ViewRefreshControlProps {
  /** 最近一次成功刷新的时间戳（ms）；null 则显示"从未" */
  lastUpdatedAt: number | null
  /** 是否正在刷新（驱动图标旋转） */
  refreshing: boolean
  /** 当前展示的数据是否可能过时（最近刷新失败） */
  stale: boolean
  /** 自动刷新开关受控值（默认 true）；未传时不渲染开关 */
  autoEnabled?: boolean
  /** 切换自动刷新开关；未传时不渲染开关 */
  onToggleAuto?: (next: boolean) => void
  /** 手动刷新回调 */
  onRefresh: () => void
}

function formatRelative(ts: number): string {
  const seconds = Math.max(0, Math.floor((Date.now() - ts) / 1000))
  if (seconds < 60) return "刚刚"
  if (seconds < 3600) {
    const m = Math.floor(seconds / 60)
    return `${m} 分钟前`
  }
  if (seconds < 86400) {
    const h = Math.floor(seconds / 3600)
    return `${h} 小时前`
  }
  const d = Math.floor(seconds / 86400)
  return `${d} 天前`
}

export function ViewRefreshControl({
  lastUpdatedAt,
  refreshing,
  stale,
  autoEnabled,
  onToggleAuto,
  onRefresh,
}: ViewRefreshControlProps) {
  const t = useTranslations("viewRefresh")

  // 自动刷新 / 手动点击都把 refreshing 拉 true，本地秒回接口会让它只闪一帧；
  // useMinSpin 把旋转兜底到至少一整圈，保证肉眼可见（详见 hook 注释）。
  const spinning = useMinSpin(refreshing)

  const timeLabel = lastUpdatedAt
    ? t("lastUpdated", { time: formatRelative(lastUpdatedAt) })
    : null

  return (
    <div className="flex items-center gap-2 text-xs text-muted-foreground">
      {/* stale 非打断提示 */}
      {stale && (
        <span className="text-destructive/80">{t("stale")}</span>
      )}

      {/* 最后更新时间 */}
      {timeLabel && <span className="tabular-nums">{timeLabel}</span>}

      {/* 自动刷新开关（US3 激活） */}
      {onToggleAuto && autoEnabled !== undefined && (
        <label className="flex items-center gap-1.5 cursor-pointer select-none">
          <span className={cn(!autoEnabled && "text-muted-foreground/50")}>
            {autoEnabled ? t("auto") : t("paused")}
          </span>
          <button
            type="button"
            role="switch"
            aria-checked={autoEnabled}
            onClick={() => onToggleAuto(!autoEnabled)}
            className={cn(
              "relative inline-flex h-5 w-9 shrink-0 items-center rounded-full transition-colors",
              autoEnabled ? "bg-primary" : "bg-muted-foreground/25",
            )}
          >
            <span
              className={cn(
                "inline-block size-3.5 rounded-full bg-background shadow-sm transition-transform",
                autoEnabled ? "translate-x-[18px]" : "translate-x-[3px]",
              )}
            />
          </button>
        </label>
      )}

      {/* 手动刷新按钮 */}
      <button
        type="button"
        onClick={onRefresh}
        disabled={spinning}
        className="inline-flex items-center justify-center size-7 rounded-md hover:bg-accent disabled:opacity-50 transition-colors"
        aria-label="Refresh"
      >
        <HugeiconsIcon
          icon={RefreshIcon}
          className={cn("size-4", spinning && "animate-spin")}
        />
      </button>
    </div>
  )
}
