"use client"

/**
 * 血缘探索器工具栏 —— common: 搜索 + 方向 + 深度 + 粒度 + 操作 + 刷新。
 *
 * 设计约束（DESIGN.md）：语义 token、无分割线、不手写 dark:、间距 gap-*。
 * 方向/粒度走新公共原语 Segmented（h-8 sm 紧凑）；深度走 Stepper。
 * 右置 ViewRefreshControl。
 */
import { useTranslations } from "next-intl"
import type { LineageDirection, LineageFilters, NodeType } from "@/lib/lineage-api"
import { Input } from "@/components/ui/input"
import { Segmented } from "@/components/ui/segmented"
import { Stepper } from "@/components/ui/stepper"
import { ViewRefreshControl } from "@/components/workspace/views/view-refresh-control"
import { AgentConfigPanel } from "@/components/workspace/views/lineage/agent-config-panel"

export interface LineageToolbarProps {
  // ── 方向 ──
  direction: LineageDirection
  onDirectionChange: (v: LineageDirection) => void

  // ── 深度 ──
  depth: number
  onDepthChange: (v: number) => void

  // ── 粒度 ──
  granularity: "TABLE" | "COLUMN"
  onGranularityChange: (v: "TABLE" | "COLUMN") => void

  // ── 搜索（US2）──
  searchQuery: string
  onSearchChange: (q: string) => void
  onSearchSubmit?: () => void

  // ── 过滤（US4+）──
  filters?: LineageFilters

  // ── 操作（US3/US4/US5，可空 = 不渲染）──
  onImpactAnalysis?: () => void
  onPathHighlight?: () => void
  onExport?: () => void
  onCopyDeepLink?: () => void

  // ── 刷新 ──
  lastRefreshMs: number | null
  refreshing: boolean
  stale: boolean
  onRefresh: () => void

  // ── 状态 ──
  hasAnchor: boolean
  loading: boolean
}

export function LineageToolbar({
  direction,
  onDirectionChange,
  depth,
  onDepthChange,
  granularity,
  onGranularityChange,
  searchQuery,
  onSearchChange,
  onSearchSubmit,
  // filters omitted for now (US4+)
  onImpactAnalysis,
  onPathHighlight,
  onExport,
  onCopyDeepLink,
  lastRefreshMs,
  refreshing,
  stale,
  onRefresh,
  hasAnchor,
  loading,
}: LineageToolbarProps) {
  const t = useTranslations("lineageView")

  const DIR_OPTIONS = [
    { value: "upstream", label: t("directionUpstream") },
    { value: "downstream", label: t("directionDownstream") },
    { value: "both", label: t("directionBoth") },
  ]

  const GRAN_OPTIONS = [
    { value: "TABLE", label: t("granularityTable") },
    { value: "COLUMN", label: t("granularityColumn") },
  ]

  return (
    <div className="flex shrink-0 items-center gap-2 px-3 py-2">
      {/* 搜索（US2） */}
      <Input
        type="text"
        value={searchQuery}
        onChange={(e) => onSearchChange(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Enter") onSearchSubmit?.()
        }}
        placeholder={t("searchPlaceholder")}
        className="h-8 max-w-[220px]"
      />

      {/* 方向 */}
      <Segmented
        options={DIR_OPTIONS}
        value={direction}
        onChange={(v) => onDirectionChange(v as LineageDirection)}
        ariaLabel={t("directionLabel")}
        disabled={loading || !hasAnchor}
      />

      {/* 深度 */}
      <Stepper
        value={depth}
        onChange={onDepthChange}
        min={1}
        max={20}
        ariaLabel={t("depthLabel")}
        disabled={loading || !hasAnchor}
      />

      {/* 粒度 */}
      <Segmented
        options={GRAN_OPTIONS}
        value={granularity}
        onChange={(v) => onGranularityChange(v as "TABLE" | "COLUMN")}
        ariaLabel={t("granularityLabel")}
        disabled={loading || !hasAnchor}
      />

      {/* 操作按钮 */}
      <div className="flex items-center gap-1">
        {onImpactAnalysis && (
          <button
            type="button"
            disabled={!hasAnchor}
            onClick={onImpactAnalysis}
            className="rounded px-2 py-1 text-xs text-muted-foreground transition-colors hover:bg-muted hover:text-foreground disabled:opacity-40"
          >
            {t("impactTitle")}
          </button>
        )}
        {onPathHighlight && (
          <button
            type="button"
            disabled={!hasAnchor}
            onClick={onPathHighlight}
            className="rounded px-2 py-1 text-xs text-muted-foreground transition-colors hover:bg-muted hover:text-foreground disabled:opacity-40"
          >
            {t("pathTitle")}
          </button>
        )}
        {onCopyDeepLink && (
          <button
            type="button"
            disabled={!hasAnchor}
            onClick={onCopyDeepLink}
            className="rounded px-2 py-1 text-xs text-muted-foreground transition-colors hover:bg-muted hover:text-foreground disabled:opacity-40"
          >
            {t("copyDeepLink")}
          </button>
        )}
        {onExport && (
          <button
            type="button"
            disabled={!hasAnchor}
            onClick={onExport}
            className="rounded px-2 py-1 text-xs text-muted-foreground transition-colors hover:bg-muted hover:text-foreground disabled:opacity-40"
          >
            {t("exportGraph")}
          </button>
        )}
      </div>

      {/* 053：血缘 AI Agent 配置入口（自包含触发按钮 + Dialog） */}
      <AgentConfigPanel />

      {/* 刷新 */}
      <div className="ml-auto">
        <ViewRefreshControl
          lastUpdatedAt={lastRefreshMs}
          refreshing={refreshing}
          stale={stale}
          onRefresh={onRefresh}
        />
      </div>
    </div>
  )
}
