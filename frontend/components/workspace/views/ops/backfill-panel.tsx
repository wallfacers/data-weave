"use client"

/**
 * 补数据实例 Tab：统一 DataTable 渲染补数据 run 列表（GET /api/ops/backfill，server 分页+筛选）。
 * 每行显示进度（success/failed/running/total）。筛选：状态多选 + 目标搜索 + 业务日期区间；
 * 预设：进行中 / 部分失败。「触发补数据」按钮在表上方头部，打开 BackfillDialog。
 */

import { useMemo, useState } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import { Add01Icon, ArrowDown02Icon } from "@hugeicons/core-free-icons"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { DataTable } from "@/components/ui/data-table"
import {
  type ColumnDef,
  type FilterDef,
  type FilterPreset,
  type FetchQuery,
  type PageResult,
  toQueryParams,
} from "@/lib/data-table"
import { authFetch, API_BASE, type ApiResponse } from "@/lib/types"
import { useFormatDateTime } from "@/hooks/use-format-date-time"
import { BackfillDialog } from "./backfill-dialog"

interface BackfillRun {
  id: string
  targetType: "task" | "workflow"
  targetId: number
  targetName?: string
  dateStart: string
  dateEnd: string
  parallelism: number
  state: "RUNNING" | "SUCCESS" | "FAILED" | "PARTIAL"
  total: number
  success: number
  failed: number
  running: number
  createdAt: string
  activeDates?: number
  heldDates?: number
}

const STATE_VARIANT: Record<string, "success" | "destructive" | "warning" | "info"> = {
  RUNNING: "info",
  SUCCESS: "success",
  FAILED: "destructive",
  PARTIAL: "warning",
}
const STATE_I18N: Record<string, string> = {
  RUNNING: "backfillStateRunning",
  SUCCESS: "backfillStateSuccess",
  FAILED: "backfillStateFailed",
  PARTIAL: "backfillStatePartial",
}

export function BackfillPanel() {
  const t = useTranslations("ops")
  const formatDateTime = useFormatDateTime()
  const [dialogOpen, setDialogOpen] = useState(false)
  const [drillRunId, setDrillRunId] = useState<string | null>(null)
  const [reloadKey, setReloadKey] = useState(0)

  const filters = useMemo<FilterDef[]>(
    () => [
      {
        key: "state",
        label: t("backfillFilterState"),
        kind: "multiSelect",
        width: "w-36",
        options: [
          { value: "RUNNING", label: t("backfillStateRunning") },
          { value: "SUCCESS", label: t("backfillStateSuccess") },
          { value: "FAILED", label: t("backfillStateFailed") },
          { value: "PARTIAL", label: t("backfillStatePartial") },
        ],
      },
      { key: "targetName", label: t("backfillFilterTarget"), kind: "search", width: "w-48", placeholder: t("backfillFilterTarget") },
      { key: "bizDate", label: t("backfillFilterBizDate"), kind: "dateRange" },
    ],
    [t],
  )

  const presets = useMemo<FilterPreset[]>(
    () => [
      { key: "running", label: t("presetBackfillRunning"), set: { state: ["RUNNING"], targetName: "", bizDate: {} } },
      { key: "partial", label: t("presetBackfillPartial"), set: { state: ["PARTIAL"], targetName: "", bizDate: {} } },
    ],
    [t],
  )

  const fetcher = async (query: FetchQuery): Promise<PageResult<BackfillRun>> => {
    const qs = toQueryParams(query, filters)
    const res = await authFetch(`${API_BASE}/api/ops/backfill?${qs.toString()}`)
    const empty = { items: [], total: 0, page: query.page, size: query.size }
    if (!res.ok) return empty
    const json = (await res.json()) as ApiResponse<unknown>
    if (json.code !== 0 || !json.data) return empty
    const o = json.data as Record<string, unknown>
    if (Array.isArray(o.items)) {
      return {
        items: o.items as BackfillRun[],
        total: (o.total as number) ?? (o.items as unknown[]).length,
        page: (o.page as number) ?? query.page,
        size: (o.size as number) ?? query.size,
      }
    }
    return empty
  }

  const columns = useMemo<ColumnDef<BackfillRun>[]>(
    () => [
      {
        key: "id",
        header: t("backfillColRun"),
        widthPct: 9,
        cellClassName: "font-mono tabular-nums text-xs",
        cell: (r) => r.id.slice(0, 8),
      },
      {
        key: "targetName",
        header: t("backfillColTarget"),
        widthPct: 18,
        cell: (r) => (
          <div title={r.targetName}>
            <div className="truncate font-medium">{r.targetName ?? `#${r.targetId}`}</div>
            <div className="text-xs text-muted-foreground">{r.targetType}</div>
          </div>
        ),
      },
      {
        key: "dates",
        header: t("backfillColDates"),
        widthPct: 16,
        cellClassName: "tabular-nums text-xs",
        cell: (r) => `${r.dateStart} ~ ${r.dateEnd}`,
      },
      {
        key: "parallelism",
        header: t("backfillColParallelism"),
        widthPct: 8,
        align: "right",
        cellClassName: "tabular-nums",
        cell: (r) => (
          <span>
            {r.parallelism}
            {r.heldDates ? (
              <div className="text-xs text-muted-foreground">
                {t("backfillThrottle", { active: r.activeDates ?? 0, held: r.heldDates })}
              </div>
            ) : null}
          </span>
        ),
      },
      {
        key: "progress",
        header: t("backfillColProgress"),
        widthPct: 21,
        cell: (r) => (
          <div className="flex items-center gap-2">
            <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-muted">
              <div
                className="h-full bg-success transition-all"
                style={{ width: `${r.total ? (r.success / r.total) * 100 : 0}%` }}
              />
            </div>
            <span className="text-xs tabular-nums text-muted-foreground">
              {t("backfillProgress", { success: r.success, failed: r.failed, running: r.running, total: r.total })}
            </span>
          </div>
        ),
      },
      {
        key: "state",
        header: t("backfillColState"),
        widthPct: 10,
        cell: (r) => (
          <Badge variant={STATE_VARIANT[r.state] ?? "info"}>{t(STATE_I18N[r.state] as never)}</Badge>
        ),
      },
      {
        key: "actions",
        header: t("colActions"),
        widthPct: 10,
        align: "right",
        cell: (r) => (
          <Button
            variant="ghost"
            size="sm"
            className="h-6 px-2 text-xs"
            onClick={() => setDrillRunId(r.id === drillRunId ? null : r.id)}
          >
            <HugeiconsIcon icon={ArrowDown02Icon} className="size-3.5" />
            {t("backfillDrill")}
          </Button>
        ),
      },
    ],
    [t, formatDateTime, drillRunId],
  )

  return (
    <div className="flex min-h-0 min-w-0 flex-1 flex-col p-5">
      <DataTable<BackfillRun>
        key={reloadKey}
        columns={columns}
        getRowId={(r) => r.id}
        mode="server"
        fetcher={fetcher}
        filters={filters}
        presets={presets}
        emptyTitle={t("backfillEmpty")}
        emptyHint={t("backfillEmptyHint")}
        toolbarActions={
          <Button size="sm" className="h-7 text-xs" onClick={() => setDialogOpen(true)}>
            <HugeiconsIcon icon={Add01Icon} className="size-3.5" />
            {t("backfillTrigger")}
          </Button>
        }
      />

      {drillRunId && (
        <div className="shrink-0 rounded-lg border bg-muted/30 p-3">
          <p className="mb-2 text-xs text-muted-foreground">
            {t("backfillDrill")}: {drillRunId.slice(0, 8)}
          </p>
          <p className="text-sm text-muted-foreground">
            Drill-down list placeholder — backend integration provides GET /backfill/{"{runId}"}.
          </p>
        </div>
      )}

      <BackfillDialog open={dialogOpen} onOpenChange={setDialogOpen} onSuccess={() => setReloadKey((k) => k + 1)} />
    </div>
  )
}
