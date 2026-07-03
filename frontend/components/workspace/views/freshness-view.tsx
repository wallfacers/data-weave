"use client"

import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import { useTranslations } from "next-intl"
import { BoxIcon } from "@hugeicons/core-free-icons"

import { Badge } from "@/components/ui/badge"
import { DataTable } from "@/components/ui/data-table"
import { type ColumnDef, type FilterDef, type FilterPreset, type FetchQuery, type PageResult } from "@/lib/data-table"
import { useFormatDateTime } from "@/hooks/use-format-date-time"
import { useRefreshSchedule } from "@/lib/workspace/use-api"
import { useProjectContext } from "@/lib/project-context"
import type { ViewProps } from "@/lib/workspace/registry"
import { ViewRefreshControl } from "./view-refresh-control"
import { FreshnessSummaryStrip } from "./freshness-summary-strip"
import {
  fetchDashboard,
  fetchFreshnessTable,
  type FreshnessDashboard,
  type FreshnessRow,
} from "@/lib/workspace/freshness-api"

/** 时效分档 → 徽章 */
function tierBadge(tier: string, t: (k: string) => string) {
  switch (tier) {
    case "FRESH":
      return <Badge variant="success">{t("badgeFresh")}</Badge>
    case "AGING":
      return <Badge variant="warning">{t("badgeAging")}</Badge>
    case "STALE":
      return <Badge variant="destructive">{t("badgeStale")}</Badge>
    case "NEVER":
    default:
      return <Badge variant="destructive">{t("badgeNeverSucceeded")}</Badge>
  }
}

function ageDisplay(ageHours: number | null, t: (k: string, v?: Record<string, number>) => string): string {
  if (ageHours == null) return t("noTrend")
  if (ageHours < 1) return t("justNow")
  if (ageHours < 24) return t("hoursAgo", { hours: ageHours })
  return t("daysAgo", { days: Math.floor(ageHours / 24) })
}

export function FreshnessView({ active }: ViewProps) {
  const t = useTranslations("freshness")
  const formatDateTime = useFormatDateTime()
  const projectId = useProjectContext((s) => s.currentProjectId)

  // ── Refresh state ──
  const [reloadSignal, setReloadSignal] = useState(0)
  const [refreshing, setRefreshing] = useState(false)
  const [lastUpdatedAt, setLastUpdatedAt] = useState<number | null>(null)
  const [autoEnabled, setAutoEnabled] = useState(true)

  const onTick = useCallback(() => setReloadSignal((n) => n + 1), [])
  const { tickNow } = useRefreshSchedule(onTick, { active, enabled: autoEnabled })
  const onLoadingChange = useCallback((loading: boolean) => setRefreshing(loading), [])
  const onLoaded = useCallback(() => setLastUpdatedAt(Date.now()), [])

  // ── Dashboard ──
  const [dashboard, setDashboard] = useState<FreshnessDashboard | null>(null)

  useEffect(() => {
    if (projectId == null) return
    let cancelled = false
    fetchDashboard(projectId).then((d) => {
      if (!cancelled) setDashboard(d)
    })
    return () => { cancelled = true }
  }, [projectId, reloadSignal])

  // ── External tier filter (US3) ──
  const [externalTier, setExternalTier] = useState<string | null>(null)

  // 时效分档卡片点击 → 触发表格重新查询
  const prevExternalTier = useRef<string | null>(null)
  useEffect(() => {
    if (prevExternalTier.current === externalTier) return
    prevExternalTier.current = externalTier
    setReloadSignal((n) => n + 1)
  }, [externalTier])

  // ── Filters ──
  const filters = useMemo<FilterDef[]>(
    () => [
      {
        key: "tiers",
        label: t("filterTier"),
        kind: "multiSelect",
        width: "w-44",
        options: [
          { value: "FRESH", label: t("badgeFresh") },
          { value: "AGING", label: t("badgeAging") },
          { value: "STALE", label: t("badgeStale") },
          { value: "NEVER", label: t("badgeNeverSucceeded") },
        ],
      },
      { key: "taskName", label: t("filterTaskName"), kind: "search", placeholder: t("filterTaskNamePh"), width: "w-44" },
    ],
    [t],
  )

  const presets = useMemo<FilterPreset[]>(
    () => [
      { key: "stale", label: t("presetStale"), set: { tiers: ["STALE"], taskName: "" } },
      { key: "neverSucceeded", label: t("presetNeverSucceeded"), set: { tiers: ["NEVER"], taskName: "" } },
    ],
    [t],
  )

  const defaultFilters = useMemo(
    () => ({ tiers: [] as string[], taskName: "" }),
    [],
  )

  // ── Fetcher ──
  const fetcher = useCallback(
    async (query: FetchQuery): Promise<PageResult<FreshnessRow>> => {
      if (projectId == null) return { items: [], total: 0, page: query.page, size: query.size }
      return fetchFreshnessTable(query, filters, projectId, externalTier)
    },
    [filters, projectId, externalTier],
  )

  // ── Columns ──
  const columns = useMemo<ColumnDef<FreshnessRow>[]>(
    () => [
      {
        key: "name",
        header: t("colWorkflowName"),
        widthPct: 28,
        cell: (r) => (
          <div className="truncate" title={r.workflowName || r.name}>
            <span className="font-medium">{r.workflowName || r.name}</span>
            {r.workflowName && (
              <span className="ml-2 text-xs text-muted-foreground">{r.name}</span>
            )}
          </div>
        ),
      },
      {
        key: "schedule",
        header: t("colSchedule"),
        widthPct: 14,
        cellClassName: "text-muted-foreground text-xs",
        cell: (r) => r.scheduleHuman ?? "—",
      },
      {
        key: "tier",
        header: t("colFreshness"),
        widthPct: 10,
        cell: (r) => (
          <span
            role="button"
            tabIndex={0}
            className="cursor-pointer"
            onClick={(e) => {
              e.stopPropagation()
              setExternalTier((prev) => (prev === r.tier ? null : r.tier))
            }}
          >
            {tierBadge(r.tier, t)}
          </span>
        ),
      },
      {
        key: "lastSuccessAt",
        header: t("colLastSuccess"),
        widthPct: 16,
        cellClassName: "tabular-nums text-muted-foreground text-xs",
        cell: (r) => formatDateTime(r.lastSuccessAt),
      },
      {
        key: "ageHours",
        header: t("colAge"),
        widthPct: 10,
        cellClassName: "text-muted-foreground text-xs",
        cell: (r) => ageDisplay(r.ageHours, t),
      },
      {
        key: "trend",
        header: t("colTrend"),
        widthPct: 22,
        cell: (r) => {
          const days = r.trend7Days
          if (!days || days.length === 0) {
            return <span className="text-muted-foreground text-xs">{t("noTrend")}</span>
          }
          const w = Math.max(days.length * 8, 16)
          return (
            <svg width={w} height={16} viewBox={`0 0 ${w} 16`} className="shrink-0" role="img" aria-label={`${days.length}-day trend`}>
              <polyline
                points={days
                  .map((v, i) => {
                    const x = i * 8 + 4
                    const y = 16 - v * 4
                    return `${x},${y}`
                  })
                  .join(" ")}
                fill="none"
                stroke="var(--chart-1)"
                strokeWidth="1.25"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
              {days.map((v, i) => {
                const x = i * 8 + 4
                const y = 16 - v * 4
                const isToday = i === days.length - 1
                return (
                  <circle
                    key={i}
                    cx={x}
                    cy={y}
                    r={isToday ? 2 : 1.25}
                    fill={isToday ? "var(--chart-1)" : "var(--background)"}
                    stroke="var(--chart-1)"
                    strokeWidth="0.75"
                  />
                )
              })}
            </svg>
          )
        },
      },
    ],
    [t, formatDateTime],
  )

  return (
    <div className="flex min-h-0 min-w-0 flex-1 flex-col p-5">
      {/* Header */}
      <div className="shrink-0 flex items-center justify-between pb-3">
        <p className="text-xs text-muted-foreground">{t("subtitle")}</p>
        <ViewRefreshControl
          lastUpdatedAt={lastUpdatedAt}
          refreshing={refreshing}
          stale={false}
          autoEnabled={autoEnabled}
          onToggleAuto={setAutoEnabled}
          onRefresh={tickNow}
        />
      </div>

      {/* Summary strip */}
      <div className="shrink-0 pb-3">
        <FreshnessSummaryStrip
          dashboard={dashboard}
          selectedTier={externalTier}
          onTierClick={(tier) => setExternalTier((prev) => (prev === tier ? null : tier))}
        />
      </div>

      {/* Data table */}
      <DataTable<FreshnessRow>
        columns={columns}
        getRowId={(r) => String(r.taskId)}
        mode="server"
        fetcher={fetcher}
        filters={filters}
        presets={presets}
        defaultFilters={defaultFilters}
        reloadSignal={reloadSignal}
        onLoadingChange={onLoadingChange}
        onLoaded={onLoaded}
        emptyIcon={BoxIcon}
        emptyTitle={t("emptyTitle")}
        emptyHint={t("emptyHint")}
      />
    </div>
  )
}
