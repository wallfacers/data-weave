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
import { useSearchParams } from "next/navigation"
import { format } from "date-fns"
import { HugeiconsIcon } from "@hugeicons/react"
import { BoxIcon, PlayIcon, StopIcon, CheckmarkCircle01Icon, Copy01Icon, FileViewIcon } from "@hugeicons/core-free-icons"
import { toast } from "sonner"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { DataTable } from "@/components/ui/data-table"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import { ConfirmDialog } from "@/components/workspace/views/shared/confirm-dialog"
import {
  type ColumnDef,
  type FilterDef,
  type FilterPreset,
  type FetchQuery,
  type PageResult,
  type SortState,
  toQueryParams,
} from "@/lib/data-table"
import { API_BASE, authFetch, type ApiResponse } from "@/lib/types"
import { useWorkspaceStore } from "@/lib/workspace/store"
import { useRefreshSchedule } from "@/lib/workspace/use-api"
import { useFormatDateTime } from "@/hooks/use-format-date-time"
import { humanizeCron as renderCron } from "@/lib/cron-format"
import { useProjectContext } from "@/lib/project-context"
import { isActionEnabled, isBulkActionEnabled } from "@/lib/instance-actions"
import { ViewRefreshControl } from "../view-refresh-control"

/** 契约① InstanceRow */
interface InstanceRow {
  id: string
  taskDefId: number
  taskDefName: string
  workflowId?: string | null
  workflowInstanceId?: string | null
  runMode: "PERIODIC" | "BACKFILL" | "MANUAL" | "TEST"
  state: string
  bizDate: string
  startedAt?: string | null
  finishedAt?: string | null
  durationMs?: number | null
  cronExpression?: string | null
  scheduledFireTime?: string | null
  env?: string  // PROD | DEV
  taskType?: string  // SQL | SHELL | PYTHON | ...
  workflowName?: string | null
  triggerType?: string | null  // CRON / MANUAL / BACKFILL
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

const TRIGGER_TYPE_I18N: Record<string, string> = {
  CRON: "triggerTypeCron",
  MANUAL: "triggerTypeManual",
  BACKFILL: "triggerTypeBackfill",
}

/** 任务类型 → i18n key；未收录的显示原始值 */
const TASK_TYPE_LABEL_KEY: Record<string, string> = {
  SQL: "taskTypeSQL",
  SHELL: "taskTypeShell",
  PYTHON: "taskTypePython",
  JAVA: "taskTypeJava",
  JAVASCRIPT: "taskTypeJavaScript",
  TYPESCRIPT: "taskTypeTypeScript",
  DATA_SYNC: "taskTypeDataSync",
  SPARK: "taskTypeSpark",
  BASH: "taskTypeBash",
}

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
  const searchParams = useSearchParams()

  const [reloadSignal, setReloadSignal] = useState(0)
  const [refreshing, setRefreshing] = useState(false)
  const [lastUpdatedAt, setLastUpdatedAt] = useState<number | null>(null)
  const [autoEnabled, setAutoEnabled] = useState(true)

  const initialSort: SortState | undefined = useMemo(() => {
    const raw = searchParams.get("sort")
    if (!raw) return { field: "scheduledFireTime", dir: "desc" }
    const parts = raw.split(":", 2)
    if (parts.length === 2) return { field: parts[0], dir: parts[1] as "asc" | "desc" }
    return { field: "scheduledFireTime", dir: "desc" }
  }, [searchParams])

  const onTick = useCallback(() => setReloadSignal((n) => n + 1), [])
  const { tickNow } = useRefreshSchedule(onTick, { active, enabled: autoEnabled, skipInitialFire: true })
  const onLoadingChange = useCallback((loading: boolean) => setRefreshing(loading), [])
  const onLoaded = useCallback(() => setLastUpdatedAt(Date.now()), [])

  // confirm dialog state
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [confirmState, setConfirmState] = useState<{
    title: string
    description: string
    destructive: boolean
    onConfirm: () => void
  }>({ title: "", description: "", destructive: false, onConfirm: () => {} })

  // 单行操作
  const submitAction = useCallback(
    async (id: string, action: string, label: string) => {
      try {
        // set-success 的 API 路径与 rerun/kill 不同
        const path = action === "set-success" ? "task-instances" : "instances"
        const res = await authFetch(`${API_BASE}/api/ops/${path}/${id}/${action}`, { method: "POST" })
        const j = (await res.json()) as ApiResponse<string>
        if (j.code === 0) {
          toast.success(t("actionSuccess", { label }))
          setReloadSignal((n) => n + 1)
        } else {
          toast.error(t("actionFailed", { label, msg: j.message }))
        }
      } catch {
        toast.error(t("actionFailed", { label, msg: "" }))
      }
    },
    [t],
  )

  /** cron → 简短可读形式：实现委托共享 util（@/lib/cron-format），文案走 i18n */
  const humanizeCron = useMemo(
    () => (cron: string | null | undefined): string =>
      renderCron(cron, (k, p) => t(k as never, p as never)),
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
      { key: "keyword", label: t("filterKeyword"), kind: "search", width: "w-48", placeholder: t("filterKeyword") },
      { key: "bizDate", label: t("filterBizDate"), kind: "date", width: "w-40" },
      { key: "workflowInstanceId", label: t("filterWorkflowInstanceId"), kind: "search", width: "w-48", placeholder: t("filterWorkflowInstanceId") },
      // 高级：排障定位维度，收进「更多筛选」
      { key: "workerNodeCode", label: t("filterWorkerNode"), kind: "search", tier: "advanced", placeholder: t("filterWorkerNode") },
      { key: "failureReason", label: t("filterFailureReason"), kind: "search", tier: "advanced", placeholder: t("filterFailureReason") },
    ],
    [t],
  )

  const presets = useMemo<FilterPreset[]>(() => {
    const today = format(new Date(), "yyyy-MM-dd")
    const empty = { runMode: "", keyword: "", bizDate: "", workerNodeCode: "", failureReason: "" }
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
        widthPct: 8,
        cell: (r) => (
          <button
            className="group inline-flex items-center gap-1 font-mono text-xs tabular-nums cursor-pointer hover:text-primary transition-colors"
            title={`${r.id} — ${t("clickToCopy")}`}
            onClick={async (e) => {
              e.stopPropagation()
              try {
                await navigator.clipboard.writeText(r.id)
                toast.success(t("copySuccessId", { value: "…" + r.id.slice(-8) }))
              } catch {
                toast.error(t("copyFailedId"))
              }
            }}
          >
            <span>…{r.id.slice(-8)}</span>
            <span className="opacity-0 group-hover:opacity-100 transition-opacity text-muted-foreground">
              <HugeiconsIcon icon={Copy01Icon} className="size-3" />
            </span>
          </button>
        ),
      },
      {
        key: "taskDefName",
        header: t("colTaskName"),
        widthPct: 13,
        cell: (r) => (
          <div className="align-top" title={taskName(r)}>
            <div className="truncate font-medium">{taskName(r)}</div>
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
        key: "workflowInstanceId",
        header: t("colWorkflowInstanceId"),
        widthPct: 7,
        cell: (r) => {
          const wiId = r.workflowInstanceId
          if (!wiId) return <span className="text-muted-foreground">—</span>
          return (
            <button
              className="group inline-flex items-center gap-1 font-mono text-xs tabular-nums cursor-pointer hover:text-primary transition-colors"
              title={`${wiId} — ${t("clickToCopy")}`}
              onClick={async (e) => {
                e.stopPropagation()
                try {
                  await navigator.clipboard.writeText(wiId)
                  toast.success(t("copySuccessId", { value: "…" + wiId.slice(-8) }))
                } catch {
                  toast.error(t("copyFailedId"))
                }
              }}
            >
              <span>…{wiId.slice(-8)}</span>
              <span className="opacity-0 group-hover:opacity-100 transition-opacity text-muted-foreground">
                <HugeiconsIcon icon={Copy01Icon} className="size-3" />
              </span>
            </button>
          )
        },
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
        key: "taskType",
        header: t("colTaskType"),
        widthPct: 5,
        cell: (r) => {
          const labelKey = r.taskType ? TASK_TYPE_LABEL_KEY[r.taskType] : null
          return (
            <Badge variant="outline" className="text-xs">
              {labelKey ? t(labelKey as never) : (r.taskType || "—")}
            </Badge>
          )
        },
      },
      {
        key: "triggerType",
        header: t("colTriggerType"),
        widthPct: 5,
        cell: (r) => {
          const labelKey = r.triggerType ? TRIGGER_TYPE_I18N[r.triggerType] : null
          return (
            <span className="text-sm">
              {labelKey ? t(labelKey as never) : (r.triggerType || "—")}
            </span>
          )
        },
      },
      {
        key: "scheduledFireTime",
        header: t("colScheduledFireTime"),
        widthPct: 11,
        sortable: true,
        sortKey: "scheduledFireTime",
        cell: (r) =>
          r.scheduledFireTime ? (
            <span className="font-mono text-sm tabular-nums">{formatDateTime(r.scheduledFireTime)}</span>
          ) : (
            <span className="text-muted-foreground">—</span>
          ),
      },
      { key: "bizDate", header: t("colBizDate"), widthPct: 10, sortable: true, sortKey: "bizDate", cellClassName: "tabular-nums text-xs" },
      {
        key: "startedAt",
        header: t("colStartedAt"),
        widthPct: 11,
        sortable: true,
        sortKey: "startedAt",
        cellClassName: "tabular-nums text-xs",
        cell: (r) => formatDateTime(r.startedAt ?? null),
      },
      {
        key: "finishedAt",
        header: t("colFinishedAt"),
        widthPct: 13,
        sortable: true,
        sortKey: "finishedAt",
        cellClassName: "tabular-nums text-xs",
        cell: (r) => formatDateTime(r.finishedAt ?? null),
      },
      {
        key: "durationMs",
        header: t("colDuration"),
        widthPct: 5,
        sortable: true,
        sortKey: "durationMs",
        align: "right",
        cellClassName: "tabular-nums text-xs",
        cell: (r) => formatDuration(r.durationMs),
      },
      {
        key: "actions",
        header: t("colActions"),
        widthPct: 8,
        cell: (r) => (
          <div className="flex items-center gap-0.5" onClick={(e) => e.stopPropagation()}>
            <Tooltip>
              <TooltipTrigger
                render={
                  <Button
                    size="icon"
                    variant="ghost"
                    className="size-7"
                    disabled={!isActionEnabled(r.state, "rerun")}
                    onClick={() => {
                      setConfirmState({
                        title: t("batchConfirm", { label: t("batchRerun") }),
                        description: t("confirmRerunDesc"),
                        destructive: false,
                        onConfirm: () => submitAction(r.id, "rerun", t("batchRerun")),
                      })
                      setConfirmOpen(true)
                    }}
                  >
                    <HugeiconsIcon icon={PlayIcon} className="size-4" />
                  </Button>
                }
              />
              <TooltipContent>{t("batchRerun")}</TooltipContent>
            </Tooltip>
            <Tooltip>
              <TooltipTrigger
                render={
                  <Button
                    size="icon"
                    variant="ghost"
                    className="size-7"
                    onClick={() => {
                      setConfirmState({
                        title: t("batchConfirm", { label: t("batchSetSuccess") }),
                        description: t("confirmSetSuccessDesc"),
                        destructive: false,
                        onConfirm: () => submitAction(r.id, "set-success", t("batchSetSuccess")),
                      })
                      setConfirmOpen(true)
                    }}
                  >
                    <HugeiconsIcon icon={CheckmarkCircle01Icon} className="size-4" />
                  </Button>
                }
              />
              <TooltipContent>{t("batchSetSuccess")}</TooltipContent>
            </Tooltip>
            <Tooltip>
              <TooltipTrigger
                render={
                  <Button
                    size="icon"
                    variant="ghost"
                    className="size-7 text-destructive hover:text-destructive"
                    disabled={!isActionEnabled(r.state, "stop")}
                    onClick={() => {
                      setConfirmState({
                        title: t("batchConfirm", { label: t("batchKill") }),
                        description: t("confirmKillDesc"),
                        destructive: true,
                        onConfirm: () => submitAction(r.id, "kill", t("batchKill")),
                      })
                      setConfirmOpen(true)
                    }}
                  >
                    <HugeiconsIcon icon={StopIcon} className="size-4" />
                  </Button>
                }
              />
              <TooltipContent>{t("batchKill")}</TooltipContent>
            </Tooltip>
            <Tooltip>
              <TooltipTrigger
                render={
                  <Button
                    size="icon"
                    variant="ghost"
                    className="size-7"
                    onClick={() => open("instance-log", { instanceId: r.id, taskName: r.taskDefName, workflowName: r.workflowName })}
                  >
                    <HugeiconsIcon icon={FileViewIcon} className="size-4" />
                  </Button>
                }
              />
              <TooltipContent>{t("btnLog")}</TooltipContent>
            </Tooltip>
          </div>
        ),
      },
    ],
    [t, formatDateTime, humanizeCron, taskName, open],
  )

  // ── server 模式取数：复用契约①，兼容 数组 / Spring Page 两种返回 ──
  // 036 项目隔离：projectId 由 useProjectContext 提供，追加到查询参数
  const projectId = useProjectContext((s) => s.currentProjectId) ?? 1
  const fetcher = async (query: FetchQuery): Promise<PageResult<InstanceRow>> => {
    const qs = toQueryParams(query, filters)
    qs.set("projectId", String(projectId))
    const res = await authFetch(`${API_BASE}/api/ops/instances?${qs.toString()}`)
    if (!res.ok) {
      const errorBody = await res.json().catch(() => null) as ApiResponse<unknown> | null
      toast.error(errorBody?.message || t("fetchInstancesFailed", { status: res.status }))
      return { items: [], total: 0, page: query.page, size: query.size }
    }
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
        initialSort={initialSort}
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
              <span className="text-xs text-destructive">{t("bulkMaxHint")}</span>
            ) : (
              <>
                <Button
                  size="sm"
                  variant="outline"
                  disabled={ids.length === 0}
                  onClick={() => {
                    setConfirmState({
                      title: t("batchConfirm", { label: t("batchRerun") }),
                      description: t("confirmBatchRerunDesc", { count: ids.length }),
                      destructive: false,
                      onConfirm: () => runBatch("rerun", ids, reload),
                    })
                    setConfirmOpen(true)
                  }}
                >
                  <HugeiconsIcon icon={PlayIcon} /> {t("batchRerun")}
                </Button>
                <Button
                  size="sm"
                  variant="outline"
                  disabled={ids.length === 0}
                  onClick={() => {
                    setConfirmState({
                      title: t("batchConfirm", { label: t("batchSetSuccess") }),
                      description: t("confirmBatchSetSuccessDesc", { count: ids.length }),
                      destructive: false,
                      onConfirm: () => runBatch("set-success", ids, reload),
                    })
                    setConfirmOpen(true)
                  }}
                >
                  <HugeiconsIcon icon={CheckmarkCircle01Icon} /> {t("batchSetSuccess")}
                </Button>
                <Button
                  size="sm"
                  variant="destructive"
                  disabled={ids.length === 0}
                  onClick={() => {
                    setConfirmState({
                      title: t("batchConfirm", { label: t("batchKill") }),
                      description: t("confirmBatchKillDesc", { count: ids.length }),
                      destructive: true,
                      onConfirm: () => runBatch("kill", ids, reload),
                    })
                    setConfirmOpen(true)
                  }}
                >
                  <HugeiconsIcon icon={StopIcon} /> {t("batchKill")}
                </Button>
              </>
            )}
          </div>
        )}
      />
      <ConfirmDialog
        open={confirmOpen}
        onOpenChange={setConfirmOpen}
        title={confirmState.title}
        description={confirmState.description}
        destructive={confirmState.destructive}
        onConfirm={() => {
          confirmState.onConfirm()
          setConfirmOpen(false)
        }}
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
