"use client"

/**
 * 任务流实例面板 —— 展示任务流实例列表（与任务实例列表并列切换）。
 *
 * 调契约①：
 *   GET /api/ops/workflow-instances?state=&triggerType=&bizDate=&page=&size=
 *
 * 与 PeriodicInstancesPanel 共享 DataTable<T> + DataTableToolbar 渲染模式。
 */

import { useMemo, useRef, useEffect } from "react"
import { useTranslations } from "next-intl"

import { Badge } from "@/components/ui/badge"
import { DataTable } from "@/components/ui/data-table"
import {
  type ColumnDef,
  type FilterDef,
  type FilterPreset,
  type FetchQuery,
  type PageResult,
} from "@/lib/data-table"
import { API_BASE, authFetch, type ApiResponse, type WorkflowInstanceRow } from "@/lib/types"
import { useFormatDateTime } from "@/hooks/use-format-date-time"

const STATE_BADGE_VARIANT: Record<string, "success" | "info" | "warning" | "destructive" | "outline"> = {
  SUCCESS: "success",
  RUNNING: "success",
  WAITING: "warning",
  DISPATCHED: "info",
  NOT_RUN: "outline",
  FAILED: "destructive",
  STOPPED: "destructive",
  PREEMPTED: "warning",
  PAUSED: "warning",
  SKIPPED: "outline",
}

const STATE_I18N_KEY: Record<string, string> = {
  SUCCESS: "stateSuccess",
  RUNNING: "stateRunning",
  FAILED: "stateFailed",
  WAITING: "stateWaiting",
  DISPATCHED: "stateRunning",
  NOT_RUN: "stateNotRun",
  STOPPED: "stateStopped",
  PREEMPTED: "statePreempted",
  PAUSED: "statePaused",
  SKIPPED: "stateSkipped",
}

const TRIGGER_TYPE_I18N: Record<string, string> = {
  CRON: "triggerTypeCron",
  MANUAL: "triggerTypeManual",
  BACKFILL: "triggerTypeBackfill",
}

const TRIGGER_OPTIONS = [
  { value: "CRON", labelKey: "triggerTypeCron" },
  { value: "MANUAL", labelKey: "triggerTypeManual" },
  { value: "BACKFILL", labelKey: "triggerTypeBackfill" },
] as const

const STATE_OPTIONS = [
  { value: "RUNNING", labelKey: "stateRunning" },
  { value: "SUCCESS", labelKey: "stateSuccess" },
  { value: "FAILED", labelKey: "stateFailed" },
  { value: "WAITING", labelKey: "stateWaiting" },
  { value: "NOT_RUN", labelKey: "stateNotRun" },
  { value: "STOPPED", labelKey: "stateStopped" },
] as const

interface WorkflowInstancesPanelProps {
  onRowClick?: (row: WorkflowInstanceRow) => void
}

export function WorkflowInstancesPanel({ onRowClick }: WorkflowInstancesPanelProps) {
  const t = useTranslations("ops")
  const formatDateTime = useFormatDateTime()
  const abortRef = useRef<AbortController | null>(null)

  useEffect(() => {
    return () => abortRef.current?.abort() // 组件卸载时取消进行中的请求
  }, [])

  // ── 筛选定义 ──
  const filters = useMemo<FilterDef[]>(
    () => [
      {
        key: "stateIn",
        label: t("filterState"),
        kind: "multiSelect",
        width: "w-32",
        options: STATE_OPTIONS.map((o) => ({ value: o.value, label: t(o.labelKey as never) })),
      },
      {
        key: "triggerType",
        label: t("filterTriggerType"),
        kind: "select",
        width: "w-32",
        options: TRIGGER_OPTIONS.map((o) => ({ value: o.value, label: t(o.labelKey as never) })),
      },
      { key: "bizDateFrom", label: t("filterBizDateFrom"), kind: "date", width: "w-40" },
      { key: "bizDateTo", label: t("filterBizDateTo"), kind: "date", width: "w-40" },
    ],
    [t],
  )

  const presets = useMemo<FilterPreset[]>(() => {
    const empty: Record<string, string> = { triggerType: "" }
    return [
      { key: "failed", label: t("presetWfFailed"), set: { ...empty, stateIn: ["FAILED"] } },
      { key: "running", label: t("presetWfRunning"), set: { ...empty, stateIn: ["RUNNING"] } },
    ]
  }, [t])

  // ── 列定义 ──
  const columns = useMemo<ColumnDef<WorkflowInstanceRow>[]>(
    () => [
      {
        key: "workflowName",
        header: t("colWorkflowName"),
        widthPct: 18,
        cell: (row: WorkflowInstanceRow) => (
          <span className="truncate font-medium">{row.workflowName}</span>
        ),
      },
      {
        key: "triggerType",
        header: t("colTriggerType"),
        widthPct: 9,
        cell: (row: WorkflowInstanceRow) => (
          <Badge variant="outline" className="text-xs">
            {t((TRIGGER_TYPE_I18N[row.triggerType] ?? row.triggerType) as never)}
          </Badge>
        ),
      },
      {
        key: "bizDate",
        header: t("colBizDate"),
        widthPct: 12,
        cell: (row: WorkflowInstanceRow) => (
          <span className="font-mono text-sm tabular-nums">{row.bizDate}</span>
        ),
      },
      {
        key: "state",
        header: t("colState"),
        widthPct: 9,
        cell: (row: WorkflowInstanceRow) => {
          const v = row.state ?? ""
          return (
            <Badge variant={STATE_BADGE_VARIANT[v] ?? "outline"} className="text-xs">
              {t((STATE_I18N_KEY[v] ?? v) as never)}
            </Badge>
          )
        },
      },
      {
        key: "totalTasks",
        header: t("colProgress"),
        widthPct: 10,
        cell: (row: WorkflowInstanceRow) => (
          <span className="font-mono text-sm tabular-nums">
            {row.completedTasks}/{row.totalTasks}
            {row.failedTasks > 0 && (
              <span className="ml-1 text-destructive">({row.failedTasks})</span>
            )}
          </span>
        ),
      },
      {
        key: "startedAt",
        header: t("colStartedAt"),
        widthPct: 13,
        cell: (row: WorkflowInstanceRow) => (
          <span className="font-mono text-sm tabular-nums">{formatDateTime(row.startedAt)}</span>
        ),
      },
      {
        key: "durationMs",
        header: t("colDuration"),
        widthPct: 8,
        cell: (row: WorkflowInstanceRow) => {
          const ms = row.durationMs
          if (ms == null) return <span className="text-muted-foreground">—</span>
          const sec = Math.floor(ms / 1000)
          const m = Math.floor(sec / 60)
          const s = sec % 60
          return (
            <span className="font-mono text-sm tabular-nums">
              {m}:{String(s).padStart(2, "0")}
            </span>
          )
        },
      },
    ],
    [t, formatDateTime],
  )

  // ── 数据获取 ──
  const fetcher = useMemo(
    () =>
      async (query: FetchQuery): Promise<PageResult<WorkflowInstanceRow>> => {
        // 取消前一次未完成的请求，防止快速切换时数据错乱
        abortRef.current?.abort()
        abortRef.current = new AbortController()
        const params = new URLSearchParams()
        params.set("page", String(query.page))
        params.set("size", String(query.size))
        for (const [k, v] of Object.entries(query.filters ?? {})) {
          if (v != null && v !== "") {
            params.set(k, Array.isArray(v) ? v.join(",") : String(v))
          }
        }
        const res = await authFetch(`${API_BASE}/api/ops/workflow-instances?${params.toString()}`, {
          signal: abortRef.current.signal,
        })
        const json: ApiResponse<{ items: WorkflowInstanceRow[]; total: number }> = await res.json()
        if (json.code !== 0 || !json.data) {
          throw new Error(json.message || "Failed to fetch workflow instances")
        }
        return { items: json.data.items, total: json.data.total, page: query.page, size: query.size }
      },
    [],
  )

  return (
    <div className="flex min-h-0 min-w-0 flex-1 flex-col p-5">
      <DataTable<WorkflowInstanceRow>
        columns={columns}
        fetcher={fetcher}
        filters={filters}
        presets={presets}
        onRowClick={onRowClick}
      />
    </div>
  )
}
