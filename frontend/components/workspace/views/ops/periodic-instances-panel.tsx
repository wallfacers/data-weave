"use client"

/**
 * 周期实例面板 —— 标杆回填：改用统一 DataTable<T> + DataTableToolbar 渲染。
 *
 * 与旧版等价：同样的列、筛选（运行模式/状态/任务/业务日期）、多选批量、布局与滚动条；
 * 版式（三段式 + 双表固定表头 + DwScroll）现由 DataTable 统一承载（见 frontend/DESIGN.md「数据表格」）。
 *
 * 调契约①：
 *   GET  /api/ops/instances?runMode=&state=&taskId=&bizDate=&page=&size=
 *   POST /api/ops/instances/batch { ids, op } → { code, data: BatchResult, outcome }
 *
 * 批量操作按 outcome 三态分流（EXECUTED / PENDING_APPROVAL / REJECTED），绝不因 code===0 误判。
 */

import { useMemo, useState, useCallback } from "react"
import { useTranslations } from "next-intl"
import { format } from "date-fns"
import { HugeiconsIcon } from "@hugeicons/react"
import { BoxIcon, PlayIcon, StopIcon, CheckmarkCircle01Icon } from "@hugeicons/core-free-icons"
import { toast } from "sonner"

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
import { API_BASE, authFetch, type ApiResponse } from "@/lib/types"
import { useWorkspaceStore } from "@/lib/workspace/store"
import { useRefreshSchedule } from "@/lib/workspace/use-api"
import { useFormatDateTime } from "@/hooks/use-format-date-time"
import { ViewRefreshControl } from "../view-refresh-control"

/** 契约① InstanceRow */
interface InstanceRow {
  id: string
  taskDefId: number
  taskDefName: string
  workflowId?: string | null
  runMode: "PERIODIC" | "BACKFILL" | "MANUAL" | "TEST"
  state: string
  bizDate: string
  startedAt?: string | null
  finishedAt?: string | null
  durationMs?: number | null
  cronExpression?: string | null
  env?: string  // PROD | DEV
  workflowName?: string | null
}

interface BatchRowResult {
  id: string
  outcome: "EXECUTED" | "PENDING_APPROVAL" | "REJECTED"
  approvalId?: string | null
}
interface BatchResult {
  requested: number
  accepted: number
  results: BatchRowResult[]
}
interface BatchResponse {
  code: number
  data: BatchResult | null
  outcome: "EXECUTED" | "PENDING_APPROVAL" | "REJECTED"
  message?: string
}

const STATE_BADGE_VARIANT: Record<string, "success" | "info" | "warning" | "destructive" | "outline"> = {
  SUCCESS: "success",
  RUNNING: "success",
  WAITING: "warning",
  WAIT_RETRY: "info",
  DISPATCHED: "info",
  NOT_RUN: "outline",
  FAILED: "destructive",
  KILLED: "destructive",
  STOPPED: "destructive",
  PAUSED: "warning",
}

const STATE_I18N: Record<string, string> = {
  SUCCESS: "stateSuccess",
  RUNNING: "stateRunning",
  FAILED: "stateFailed",
  WAITING: "stateWaiting",
  WAIT_RETRY: "stateWaiting",
  DISPATCHED: "stateRunning",
  NOT_RUN: "stateNotRun",
  STOPPED: "stateStopped",
  KILLED: "stateKilled",
  PAUSED: "statePaused",
}

const RUNMODE_OPTIONS = [
  { value: "PERIODIC", labelKey: "filterRunModePeriodic" },
  { value: "BACKFILL", labelKey: "filterRunModeBackfill" },
  { value: "MANUAL", labelKey: "filterRunModeManual" },
  { value: "TEST", labelKey: "filterRunModeTest" },
] as const

const STATE_OPTIONS = [
  { value: "RUNNING", labelKey: "stateRunning" },
  { value: "SUCCESS", labelKey: "stateSuccess" },
  { value: "FAILED", labelKey: "stateFailed" },
  { value: "WAITING", labelKey: "stateWaiting" },
  { value: "NOT_RUN", labelKey: "stateNotRun" },
  { value: "STOPPED", labelKey: "stateStopped" },
  { value: "KILLED", labelKey: "stateKilled" },
  { value: "PAUSED", labelKey: "statePaused" },
] as const

export function PeriodicInstancesPanel({
  initialFilter,
  active,
}: {
  initialFilter?: Record<string, string>
  active?: boolean
}) {
  const t = useTranslations("ops")
  const formatDateTime = useFormatDateTime()
  const open = useWorkspaceStore((s) => s.open)

  const [reloadSignal, setReloadSignal] = useState(0)
  const [refreshing, setRefreshing] = useState(false)
  const [lastUpdatedAt, setLastUpdatedAt] = useState<number | null>(null)
  const [autoEnabled, setAutoEnabled] = useState(true)

  const onTick = useCallback(() => setReloadSignal((n) => n + 1), [])
  const { tickNow } = useRefreshSchedule(onTick, { active, enabled: autoEnabled })
  const onLoadingChange = useCallback((loading: boolean) => setRefreshing(loading), [])
  const onLoaded = useCallback(() => setLastUpdatedAt(Date.now()), [])

  /** cron → 简短可读形式（仅处理标准 6 段 Quartz cron），文案走 i18n */
  const humanizeCron = useMemo(
    () =>
      (cron: string | null | undefined): string => {
        if (!cron) return "—"
        const parts = cron.trim().split(/\s+/)
        if (parts.length < 6) return cron
        const [sec, min, hour, dom, , dow] = parts
        const hh = hour.padStart(2, "0")
        const mm = min.padStart(2, "0")
        if (sec === "0" && dom === "*" && dow === "?") {
          return t("cronDaily", { time: min === "0" ? `${hh}:00` : `${hh}:${mm}` })
        }
        if (sec === "0" && dom === "*" && dow === "1-5") {
          return t("cronWeekday", { time: `${hh}:${mm}` })
        }
        if (sec === "0" && min === "0" && dom !== "*" && dow === "?") {
          return t("cronMonthly", { day: dom, time: `${hh}:00` })
        }
        return cron
      },
    [t],
  )

  const taskName = useMemo(
    () =>
      (inst: InstanceRow): string =>
        inst.taskDefName ||
        (inst.taskDefId != null ? t("taskFallback", { id: inst.taskDefId }) : t("taskFallbackUnknown")),
    [t],
  )

  // ── 筛选定义（与旧版等价：runMode/state/taskId/bizDate；新增预设） ──
  const filters = useMemo<FilterDef[]>(
    () => [
      {
        key: "runMode",
        label: t("filterRunMode"),
        kind: "select",
        width: "w-32",
        options: RUNMODE_OPTIONS.map((o) => ({ value: o.value, label: t(o.labelKey as never) })),
      },
      {
        key: "stateIn",
        label: t("filterState"),
        kind: "multiSelect",
        width: "w-32",
        options: STATE_OPTIONS.map((o) => ({ value: o.value, label: t(o.labelKey as never) })),
      },
      { key: "taskId", label: t("filterTaskId"), kind: "search", width: "w-32", placeholder: t("filterTaskId") },
      { key: "bizDate", label: t("filterBizDate"), kind: "date", width: "w-40" },
      // 高级：排障定位维度，收进「更多筛选」
      { key: "workerNodeCode", label: t("filterWorkerNode"), kind: "search", tier: "advanced", placeholder: t("filterWorkerNode") },
      { key: "failureReason", label: t("filterFailureReason"), kind: "search", tier: "advanced", placeholder: t("filterFailureReason") },
    ],
    [t],
  )

  const presets = useMemo<FilterPreset[]>(() => {
    const today = format(new Date(), "yyyy-MM-dd")
    const empty = { runMode: "", taskId: "", bizDate: "", workerNodeCode: "", failureReason: "" }
    return [
      { key: "todayFailed", label: t("presetTodayFailed"), set: { ...empty, bizDate: today, stateIn: ["FAILED"] } },
      { key: "running", label: t("presetRunning"), set: { ...empty, stateIn: ["RUNNING"] } },
    ]
  }, [t])

  const defaultFilters = useMemo(() => ({ ...(initialFilter ?? {}) }), [initialFilter])

  // ── 列定义 ──
  const columns = useMemo<ColumnDef<InstanceRow>[]>(
    () => [
      {
        key: "id",
        header: t("colInstance"),
        widthPct: 9,
        headClassName: "font-mono",
        cellClassName: "font-mono tabular-nums text-xs",
        cell: (r) => r.id.slice(0, 8),
      },
      {
        key: "taskDefName",
        header: t("colTaskName"),
        widthPct: 13,
        cell: (r) => (
          <div className="align-top" title={taskName(r)}>
            <div className="truncate font-medium">{taskName(r)}</div>
            {r.runMode === "PERIODIC" && r.cronExpression && (
              <div className="truncate text-xs text-muted-foreground">
                {humanizeCron(r.cronExpression)}
              </div>
            )}
          </div>
        ),
      },
      {
        key: "workflowName",
        header: t("colWorkflow"),
        widthPct: 10,
        cell: (r) => (
          <span className="truncate text-sm">{r.workflowName || "—"}</span>
        ),
      },
      {
        key: "state",
        header: t("colState"),
        widthPct: 8,
        cell: (r) => {
          const variant = STATE_BADGE_VARIANT[r.state] ?? "outline"
          const labelKey = STATE_I18N[r.state]
          return (
            <Badge variant={variant} className={variant === "outline" ? "text-muted-foreground" : undefined}>
              {labelKey ? t(labelKey as never) : r.state}
            </Badge>
          )
        },
      },
      {
        key: "env",
        header: t("colEnv"),
        widthPct: 5,
        cell: (r) => (
          <Badge variant={r.env === "DEV" ? "secondary" : "default"} className="text-xs">
            {r.env ?? "PROD"}
          </Badge>
        ),
      },
      {
        key: "schedule",
        header: t("colSchedule"),
        widthPct: 10,
        cellClassName: "tabular-nums text-xs",
        cell: (r) => humanizeCron(r.cronExpression),
      },
      { key: "bizDate", header: t("colBizDate"), widthPct: 10, cellClassName: "tabular-nums text-xs" },
      {
        key: "startedAt",
        header: t("colStartedAt"),
        widthPct: 11,
        cellClassName: "tabular-nums text-xs",
        cell: (r) => formatDateTime(r.startedAt ?? null),
      },
      {
        key: "finishedAt",
        header: t("colFinishedAt"),
        widthPct: 13,
        cellClassName: "tabular-nums text-xs",
        cell: (r) => formatDateTime(r.finishedAt ?? null),
      },
      {
        key: "durationMs",
        header: t("colDuration"),
        widthPct: 5,
        align: "right",
        cellClassName: "tabular-nums text-xs",
        cell: (r) => formatDuration(r.durationMs),
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
            onClick={() => open("workflow-instance-detail", { instanceId: r.id })}
          >
            {t("btnLog")}
          </Button>
        ),
      },
    ],
    [t, formatDateTime, humanizeCron, taskName, open],
  )

  // ── server 模式取数：复用契约①，兼容 数组 / Spring Page 两种返回 ──
  const fetcher = async (query: FetchQuery): Promise<PageResult<InstanceRow>> => {
    const qs = toQueryParams(query, filters)
    const res = await authFetch(`${API_BASE}/api/ops/instances?${qs.toString()}`)
    if (!res.ok) return { items: [], total: 0, page: query.page, size: query.size }
    const json = (await res.json()) as ApiResponse<unknown>
    if (json.code !== 0 || !json.data) return { items: [], total: 0, page: query.page, size: query.size }
    const d = json.data
    if (Array.isArray(d)) {
      return { items: d as InstanceRow[], total: d.length, page: 1, size: d.length || query.size }
    }
    const o = d as Record<string, unknown>
    if (Array.isArray(o.content)) {
      return {
        items: o.content as InstanceRow[],
        total: (o.totalElements as number) ?? (o.content as unknown[]).length,
        page: ((o.number as number) ?? 0) + 1,
        size: (o.size as number) ?? query.size,
      }
    }
    if (Array.isArray(o.items)) {
      return {
        items: o.items as InstanceRow[],
        total: (o.total as number) ?? (o.items as unknown[]).length,
        page: (o.page as number) ?? query.page,
        size: (o.size as number) ?? query.size,
      }
    }
    return { items: [], total: 0, page: query.page, size: query.size }
  }

  async function runBatch(op: "rerun" | "kill" | "set-success", ids: string[], reload: () => void) {
    if (ids.length === 0) {
      toast.error(t("batchNoSelection"))
      return
    }
    const opLabel =
      op === "rerun" ? t("batchRerun") : op === "kill" ? t("batchKill") : t("batchSetSuccess")
    try {
      const res = await authFetch(`${API_BASE}/api/ops/instances/batch`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ ids, op }),
      })
      const json = (await res.json().catch(() => null)) as BatchResponse | null
      if (!json || json.code !== 0) {
        toast.error(t("actionFailed", { label: opLabel, msg: json?.message ?? `HTTP ${res.status}` }))
        return
      }
      summarizeOutcome(json, opLabel, t)
      reload()
    } catch (e) {
      toast.error(t("actionFailed", { label: opLabel, msg: e instanceof Error ? e.message : t("networkError") }))
    }
  }

  return (
    <div className="flex min-h-0 min-w-0 flex-1 flex-col p-5">
      <DataTable<InstanceRow>
        columns={columns}
        getRowId={(r) => r.id}
        mode="server"
        fetcher={fetcher}
        filters={filters}
        presets={presets}
        defaultFilters={defaultFilters}
        selectable
        reloadSignal={reloadSignal}
        onLoadingChange={onLoadingChange}
        onLoaded={onLoaded}
        toolbarActions={
          <ViewRefreshControl
            lastUpdatedAt={lastUpdatedAt}
            refreshing={refreshing}
            stale={false}
            autoEnabled={autoEnabled}
            onToggleAuto={setAutoEnabled}
            onRefresh={tickNow}
          />
        }
        emptyIcon={BoxIcon}
        emptyTitle={t("emptyTitle")}
        emptyHint={t("emptyHint")}
        bulkActions={(ids, reload) => (
          <div className="flex items-center gap-1">
            {ids.length > 100 ? (
              <span className="text-xs text-destructive">{t("bulkMaxHintWithCount", { count: ids.length })}</span>
            ) : (
              <>
                <Button size="sm" className="h-8 text-xs" disabled={ids.length === 0} onClick={() => runBatch("rerun", ids, reload)}>
                  <HugeiconsIcon icon={PlayIcon} className="size-3.5" />
                  {t("batchRerun")}
                </Button>
                <Button size="sm" className="h-8 text-xs" disabled={ids.length === 0} onClick={() => runBatch("set-success", ids, reload)}>
                  <HugeiconsIcon icon={CheckmarkCircle01Icon} className="size-3.5" />
                  {t("batchSetSuccess")}
                </Button>
                <Button size="sm" variant="destructive" className="h-8 text-xs" disabled={ids.length === 0} onClick={() => runBatch("kill", ids, reload)}>
                  <HugeiconsIcon icon={StopIcon} className="size-3.5" />
                  {t("batchKill")}
                </Button>
              </>
            )}
          </div>
        )}
      />
    </div>
  )
}

function formatDuration(ms: number | null | undefined): string {
  if (ms == null) return "—"
  const sec = Math.round(ms / 1000)
  if (sec < 60) return `${sec}s`
  const min = Math.floor(sec / 60)
  const s = sec % 60
  if (min < 60) return `${min}m ${s}s`
  const h = Math.floor(min / 60)
  const m = min % 60
  return `${h}h ${m}m`
}

function summarizeOutcome(json: BatchResponse, opLabel: string, t: (k: string, v?: Record<string, number>) => string) {
  const results = json.data?.results ?? []
  const executed = results.filter((r) => r.outcome === "EXECUTED").length
  const pending = results.filter((r) => r.outcome === "PENDING_APPROVAL").length
  const rejected = results.filter((r) => r.outcome === "REJECTED").length

  if (pending > 0 && executed === 0 && rejected === 0) {
    toast.info(`${opLabel} · ${t("outcomePendingApproval")} (${pending})`)
    return
  }
  const parts: string[] = []
  if (executed > 0) parts.push(`${t("outcomeExecuted")} ${executed}`)
  if (pending > 0) parts.push(`${t("outcomePendingApproval")} ${pending}`)
  if (rejected > 0) parts.push(`${t("outcomeRejected")} ${rejected}`)
  const summary = parts.join(" · ")
  if (rejected > 0) toast.error(`${opLabel} · ${summary}`)
  else if (pending > 0) toast.info(`${opLabel} · ${summary}`)
  else toast.success(`${opLabel} · ${summary}`)
}
