"use client"

/**
 * 任务流实例面板 —— 展示任务流实例列表（与任务实例列表并列切换）。
 *
 * 调契约①：
 *   GET /api/ops/workflow-instances?state=&triggerType=&bizDate=&page=&size=
 *
 * 与 PeriodicInstancesPanel 共享 DataTable<T> + DataTableToolbar 渲染模式。
 *
 * 036 功能2：每行支持操作按钮（重跑全部 / 从失败点恢复 / 停止 / 查看 DAG）。
 * 036 功能3：行点击查看 DAG 改为行选择 + 操作列按钮。
 */

import { useMemo, useRef, useEffect, useCallback, useState } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  PlayIcon,
  StopIcon,
  CheckmarkCircle01Icon,
  Share08Icon,
  Copy01Icon,
} from "@hugeicons/core-free-icons"
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
  toQueryParams,
} from "@/lib/data-table"
import { API_BASE, authFetch, type ApiResponse, type WorkflowInstanceRow } from "@/lib/types"
import { useRefreshSchedule } from "@/lib/workspace/use-api"
import { useFormatDateTime } from "@/hooks/use-format-date-time"
import { humanizeCron } from "@/lib/cron-format"
import { useProjectContext } from "@/lib/project-context"
import { ViewRefreshControl } from "../view-refresh-control"

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
  onViewDag?: (row: WorkflowInstanceRow) => void
  active?: boolean
}

export function WorkflowInstancesPanel({ onViewDag, active }: WorkflowInstancesPanelProps) {
  const t = useTranslations("ops")
  const formatDateTime = useFormatDateTime()
  const abortRef = useRef<AbortController | null>(null)
  const projectId = useProjectContext((s) => s.currentProjectId) ?? 1

  const [reloadSignal, setReloadSignal] = useState(0)
  const [refreshing, setRefreshing] = useState(false)
  const [lastUpdatedAt, setLastUpdatedAt] = useState<number | null>(null)
  const [autoEnabled, setAutoEnabled] = useState(true)

  // ── 确认对话框状态 ──
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [confirmState, setConfirmState] = useState<{
    title: string
    description: string
    destructive: boolean
    onConfirm: () => void
  }>({ title: "", description: "", destructive: false, onConfirm: () => {} })

  const onTick = useCallback(() => setReloadSignal((n) => n + 1), [])
  const { tickNow } = useRefreshSchedule(onTick, { active, enabled: autoEnabled })
  const onLoadingChange = useCallback((loading: boolean) => setRefreshing(loading), [])
  const onLoaded = useCallback(() => setLastUpdatedAt(Date.now()), [])

  useEffect(() => {
    return () => abortRef.current?.abort() // 组件卸载时取消进行中的请求
  }, [])

  // 批量操作
  const runBatch = useCallback(async (op: string, ids: string[], reload: () => void) => {
    try {
      const res = await authFetch(`${API_BASE}/api/ops/instances/batch`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ ids, op }),
      })
      const j = (await res.json()) as ApiResponse<{ outcome: string }>
      if (j.code === 0) {
        toast.success(t("batchSuccess"))
        reload()
      } else {
        toast.error(t("batchError"))
      }
    } catch {
      toast.error(t("batchError"))
    }
  }, [t])

  // ── 单行操作 ──
  const submitAction = useCallback(
    async (id: string, action: string, label: string) => {
      try {
        const res = await authFetch(`${API_BASE}/api/ops/instances/${id}/${action}`, { method: "POST" })
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
      {
        key: "scheduledFireTimeFrom",
        label: t("filterScheduledFireTimeFrom"),
        kind: "date",
        width: "w-52",
        showTime: true,
      },
      {
        key: "scheduledFireTimeTo",
        label: t("filterScheduledFireTimeTo"),
        kind: "date",
        width: "w-52",
        showTime: true,
      },
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
    () => {
      // next-intl 的 t 是字面量 key 类型，包一层适配 humanizeCron 的 CronTranslator
      const translate = (k: string, p?: Record<string, unknown>) => t(k as never, p as never)
      return [
      {
        key: "id",
        header: t("colInstanceId"),
        widthPct: 8,
        cell: (row: WorkflowInstanceRow) => (
          <button
            className="group inline-flex items-center gap-1 font-mono text-xs tabular-nums cursor-pointer hover:text-primary transition-colors"
            title={`${row.id} — ${t("clickToCopy")}`}
            onClick={async (e) => {
              e.stopPropagation()
              try {
                await navigator.clipboard.writeText(row.id)
                toast.success(t("copySuccessId", { value: "…" + row.id.slice(-8) }))
              } catch {
                toast.error(t("copyFailedId"))
              }
            }}
          >
            <span>…{row.id.slice(-8)}</span>
            <span className="opacity-0 group-hover:opacity-100 transition-opacity text-muted-foreground">
              <HugeiconsIcon icon={Copy01Icon} className="size-3" />
            </span>
          </button>
        ),
      },
      {
        key: "workflowName",
        header: t("colWorkflowName"),
        widthPct: 10,
        cell: (row: WorkflowInstanceRow) => (
          <span className="truncate font-medium">{row.workflowName}</span>
        ),
      },
      {
        key: "triggerType",
        header: t("colTriggerType"),
        widthPct: 5,
        cell: (row: WorkflowInstanceRow) => (
          <Badge variant="outline" className="text-xs">
            {t((TRIGGER_TYPE_I18N[row.triggerType] ?? row.triggerType) as never)}
          </Badge>
        ),
      },
      {
        key: "env",
        header: t("colEnv"),
        widthPct: 4,
        cell: (row: WorkflowInstanceRow) => (
          <Badge variant={row.env === "DEV" ? "secondary" : "default"} className="text-xs">
            {row.env ?? "PROD"}
          </Badge>
        ),
      },
      {
        key: "bizDate",
        header: t("colBizDate"),
        widthPct: 9,
        cell: (row: WorkflowInstanceRow) => (
          <span className="font-mono text-sm tabular-nums">{row.bizDate}</span>
        ),
      },
      {
        key: "cronExpression",
        header: t("colCron"),
        widthPct: 7,
        cell: (row: WorkflowInstanceRow) => (
          <span className="truncate font-mono text-xs tabular-nums" title={row.cronExpression ?? ""}>
            {humanizeCron(row.cronExpression, translate)}
          </span>
        ),
      },
      {
        key: "scheduledFireTime",
        header: t("colScheduledFireTime"),
        widthPct: 16,
        cell: (row: WorkflowInstanceRow) =>
          row.scheduledFireTime ? (
            <span className="font-mono text-sm tabular-nums">{formatDateTime(row.scheduledFireTime)}</span>
          ) : (
            <span className="text-muted-foreground">—</span>
          ),
      },
      {
        key: "priority",
        header: t("colPriority"),
        widthPct: 5,
        cell: (row: WorkflowInstanceRow) => (
          <span className="font-mono text-sm tabular-nums">{row.priority ?? "—"}</span>
        ),
      },
      {
        key: "workflowVersionNo",
        header: t("colVersion"),
        widthPct: 5,
        cell: (row: WorkflowInstanceRow) =>
          row.workflowVersionNo != null ? (
            <span className="font-mono text-sm tabular-nums">v{row.workflowVersionNo}</span>
          ) : (
            <span className="text-muted-foreground">—</span>
          ),
      },
      {
        key: "state",
        header: t("colState"),
        widthPct: 7,
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
        widthPct: 5,
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
        key: "finishedAt",
        header: t("colFinishedAt"),
        widthPct: 11,
        cell: (row: WorkflowInstanceRow) =>
          row.finishedAt ? (
            <span className="font-mono text-sm tabular-nums">{formatDateTime(row.finishedAt)}</span>
          ) : (
            <span className="text-muted-foreground">—</span>
          ),
      },
      {
        key: "durationMs",
        header: t("colDuration"),
        widthPct: 4,
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
      {
        key: "actions",
        header: t("colActions"),
        widthPct: 8,
        cell: (row: WorkflowInstanceRow) => (
          <div className="flex items-center gap-0.5" onClick={(e) => e.stopPropagation()}>
            <Tooltip>
              <TooltipTrigger
                render={
                  <Button
                    size="icon"
                    variant="ghost"
                    className="size-7"
                    onClick={() => {
                      setConfirmState({
                        title: t("batchConfirm", { label: t("rerunAll") }),
                        description: t("confirmRerunDesc"),
                        destructive: false,
                        onConfirm: () => submitAction(row.id, "rerun", t("rerunAll")),
                      })
                      setConfirmOpen(true)
                    }}
                  >
                    <HugeiconsIcon icon={PlayIcon} className="size-4" />
                  </Button>
                }
              />
              <TooltipContent>{t("rerunAll")}</TooltipContent>
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
                        title: t("batchConfirm", { label: t("recover") }),
                        description: t("confirmRecoverDesc"),
                        destructive: false,
                        onConfirm: () => submitAction(row.id, "recover", t("recover")),
                      })
                      setConfirmOpen(true)
                    }}
                  >
                    <HugeiconsIcon icon={CheckmarkCircle01Icon} className="size-4" />
                  </Button>
                }
              />
              <TooltipContent>{t("recover")}</TooltipContent>
            </Tooltip>
            <Tooltip>
              <TooltipTrigger
                render={
                  <Button
                    size="icon"
                    variant="ghost"
                    className="size-7 text-destructive hover:text-destructive"
                    onClick={() => {
                      setConfirmState({
                        title: t("batchConfirm", { label: t("killTask") }),
                        description: t("confirmKillDesc"),
                        destructive: true,
                        onConfirm: () => submitAction(row.id, "kill", t("killTask")),
                      })
                      setConfirmOpen(true)
                    }}
                  >
                    <HugeiconsIcon icon={StopIcon} className="size-4" />
                  </Button>
                }
              />
              <TooltipContent>{t("killTask")}</TooltipContent>
            </Tooltip>
            <Tooltip>
              <TooltipTrigger
                render={
                  <Button
                    size="icon"
                    variant="ghost"
                    className="size-7"
                    onClick={() => onViewDag?.(row)}
                  >
                    <HugeiconsIcon icon={Share08Icon} className="size-4" />
                  </Button>
                }
              />
              <TooltipContent>{t("viewDag")}</TooltipContent>
            </Tooltip>
          </div>
        ),
      },
      ]
    },
    [t, formatDateTime, submitAction, onViewDag],
  )

  // ── 数据获取 ──
  const fetcher = useMemo(
    () =>
      async (query: FetchQuery): Promise<PageResult<WorkflowInstanceRow>> => {
        // 取消前一次未完成的请求，防止快速切换时数据错乱
        abortRef.current?.abort()
        abortRef.current = new AbortController()
        const qs = toQueryParams(query, filters)
        qs.set("projectId", String(projectId))
        const res = await authFetch(`${API_BASE}/api/ops/workflow-instances?${qs.toString()}`, {
          signal: abortRef.current.signal,
        })
        const json: ApiResponse<{ items: WorkflowInstanceRow[]; total: number }> = await res.json()
        if (json.code !== 0 || !json.data) {
          throw new Error(json.message || "Failed to fetch workflow instances")
        }
        return { items: json.data.items, total: json.data.total, page: query.page, size: query.size }
      },
    [projectId, filters],
  )

  return (
    <div className="flex min-h-0 min-w-0 flex-1 flex-col p-5">
      <DataTable<WorkflowInstanceRow>
        columns={columns}
        fetcher={fetcher}
        filters={filters}
        presets={presets}
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
