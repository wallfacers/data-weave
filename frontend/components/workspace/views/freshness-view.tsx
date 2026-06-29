"use client"

import { useCallback, useMemo } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import { BoxIcon, RefreshIcon } from "@hugeicons/core-free-icons"

import { Badge } from "@/components/ui/badge"
import { DataTable } from "@/components/ui/data-table"
import { type ColumnDef, type FilterDef, type FilterPreset, type FetchQuery, type PageResult, toQueryParams } from "@/lib/data-table"
import { API_BASE, authFetch, type ApiResponse } from "@/lib/types"
import { useFormatDateTime } from "@/hooks/use-format-date-time"

interface FreshnessRow {
  taskId: number
  name: string
  tier: "FRESH" | "AGING" | "STALE" | "NEVER"
  lastSuccessAt: string | null
  ageHours: number | null
}

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

/** 时效时长人话表达（基于后端返回的 ageHours，不依赖 Date.now()） */
function ageDisplay(ageHours: number | null, t: (k: string, v?: Record<string, number>) => string): string {
  if (ageHours == null) return "—"
  if (ageHours < 1) return t("justNow")
  if (ageHours < 24) return t("hoursAgo", { hours: ageHours })
  return t("daysAgo", { days: Math.floor(ageHours / 24) })
}

export function FreshnessView() {
  const t = useTranslations("freshness")
  const formatDateTime = useFormatDateTime()

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

  const fetcher = useCallback(
    async (query: FetchQuery): Promise<PageResult<FreshnessRow>> => {
      const qs = toQueryParams(query, filters)
      // 默认 worst_first（最陈旧在前）
      if (!qs.has("sort")) {
        qs.set("sort", "worst_first")
      }
      const res = await authFetch(`${API_BASE}/api/freshness?${qs.toString()}`)
      if (!res.ok) return { items: [], total: 0, page: query.page, size: query.size }
      const json = (await res.json()) as ApiResponse<unknown>
      if (json.code !== 0 || !json.data) return { items: [], total: 0, page: query.page, size: query.size }
      const d = json.data as Record<string, unknown>
      if (Array.isArray(d.items)) {
        return {
          items: d.items as FreshnessRow[],
          total: (d.total as number) ?? (d.items as unknown[]).length,
          page: ((d.page as number) ?? 0) + 1,
          size: (d.size as number) ?? query.size,
        }
      }
      return { items: [], total: 0, page: query.page, size: query.size }
    },
    [filters],
  )

  const columns = useMemo<ColumnDef<FreshnessRow>[]>(
    () => [
      {
        key: "taskId",
        header: t("colTask"),
        widthPct: 8,
        headClassName: "font-mono",
        cellClassName: "font-mono tabular-nums text-xs",
        cell: (r) => String(r.taskId),
      },
      {
        key: "name",
        header: t("colTaskName"),
        widthPct: 28,
        cell: (r) => (
          <div className="truncate font-medium" title={r.name}>
            {r.name}
          </div>
        ),
      },
      {
        key: "tier",
        header: t("colFreshness"),
        widthPct: 10,
        cell: (r) => tierBadge(r.tier, t),
      },
      {
        key: "lastSuccessAt",
        header: t("colLastSuccess"),
        widthPct: 18,
        cellClassName: "tabular-nums text-muted-foreground text-xs",
        cell: (r) => formatDateTime(r.lastSuccessAt),
      },
      {
        key: "ageHours",
        header: t("colAge"),
        widthPct: 10,
        cellClassName: "text-muted-foreground",
        cell: (r) => ageDisplay(r.ageHours, t),
      },
    ],
    [t, formatDateTime],
  )

  return (
    <div className="flex flex-1 flex-col gap-3 px-6">
      <div className="shrink-0 border-b py-4">
        <div className="flex items-center gap-2">
          <HugeiconsIcon icon={RefreshIcon} className="size-5 text-primary" />
          <h1 className="text-lg font-semibold tracking-tight">{t("title")}</h1>
        </div>
        <p className="mt-1 text-xs text-muted-foreground">{t("subtitle")}</p>
      </div>
      <div className="flex-1 overflow-hidden">
        <DataTable<FreshnessRow>
          columns={columns}
          getRowId={(r) => String(r.taskId)}
          mode="server"
          fetcher={fetcher}
          filters={filters}
          presets={presets}
          defaultFilters={defaultFilters}
          emptyIcon={BoxIcon}
          emptyTitle={t("emptyTitle")}
          emptyHint={t("emptyHint")}
        />
      </div>
    </div>
  )
}
