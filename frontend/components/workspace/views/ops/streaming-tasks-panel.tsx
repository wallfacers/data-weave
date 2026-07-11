"use client"

/**
 * 062 实时任务运维面板（US1 视图 + US2 最新日志入口）。
 *
 * 与「任务实例」并列、紧随其后的独立面板（FR-001/002）：仅 long_running 实例，集中展示
 * 运行状态 / 已连续运行时长 / 最近检查点 / 重启情况 / worker 断连漂移。近实时刷新复用
 * useRefreshSchedule；最新日志复用既有实例日志抽屉（open("instance-log")）。
 *
 * 调契约①：GET /api/ops/streaming-tasks?state=&keyword=&projectId=&page=&size=
 *
 * 停止（保留进度）/ 强制终止 / 恢复续跑 等写操作在 US3/US4/US5 增量接入本面板。
 */

import { useCallback, useMemo, useState } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import { Activity01Icon, FileViewIcon, StopIcon, Cancel01Icon, RefreshIcon } from "@hugeicons/core-free-icons"
import { toast } from "sonner"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { DataTable } from "@/components/ui/data-table"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import { ConfirmDialog } from "@/components/workspace/views/shared/confirm-dialog"
import { ResumeCheckpointDialog } from "./resume-checkpoint-dialog"
import {
  type ColumnDef,
  type FetchQuery,
  type PageResult,
  toQueryParams,
} from "@/lib/data-table"
import { API_BASE, authFetch, type ApiResponse } from "@/lib/types"
import { useWorkspaceStore } from "@/lib/workspace/store"
import { useRefreshSchedule } from "@/lib/workspace/use-api"
import { useProjectContext } from "@/lib/project-context"
import { ViewRefreshControl } from "../view-refresh-control"

interface CheckpointView {
  id: string
  ordinal: number
  status: string
  checkpointPath: string
  completedAt?: string | null
  sizeBytes?: number | null
  expired: boolean
  resumable: boolean
}

interface StreamingTaskRow {
  instanceId: string
  taskDefId?: number | null
  taskName: string
  state: string
  longRunning: boolean
  startedAt?: string | null
  durationSeconds?: number | null
  businessAttempt: number
  infraRedispatchCount: number
  lastCheckpoint?: CheckpointView | null
  externalJobHandlePresent: boolean
  workerOnline: boolean
}

/** 实时任务状态 → badge 变体。SUSPENDED 用 warning 醒目（一等化，US5）。 */
export const STATE_BADGE: Record<string, "success" | "info" | "warning" | "destructive" | "outline"> = {
  RUNNING: "success",
  DISPATCHED: "info",
  WAITING: "info",
  STOPPED: "outline",
  SUSPENDED: "warning",
  FAILED: "destructive",
}

/** 实时任务状态 → i18n key（streamingTasks 命名空间）。 */
const STATE_I18N: Record<string, string> = {
  RUNNING: "stateRunning",
  DISPATCHED: "stateRecovering",
  WAITING: "stateRecovering",
  STOPPED: "stateStopped",
  SUSPENDED: "stateSuspended",
  FAILED: "stateFailed",
}

/** 秒 → 可读时长（Xd Xh / Xh Ym / Xm Ys / Xs）。 */
export function humanizeDuration(sec: number | null | undefined): string {
  if (sec == null || sec < 0) return "—"
  const d = Math.floor(sec / 86400)
  const h = Math.floor((sec % 86400) / 3600)
  const m = Math.floor((sec % 3600) / 60)
  const s = sec % 60
  if (d > 0) return `${d}d ${h}h`
  if (h > 0) return `${h}h ${m}m`
  if (m > 0) return `${m}m ${s}s`
  return `${s}s`
}

export function StreamingTasksPanel({ active }: { active?: boolean }) {
  const t = useTranslations("streamingTasks")
  const open = useWorkspaceStore((s) => s.open)
  const projectId = useProjectContext((s) => s.currentProjectId) ?? 1

  const [reloadSignal, setReloadSignal] = useState(0)
  const [refreshing, setRefreshing] = useState(false)
  const [lastUpdatedAt, setLastUpdatedAt] = useState<number | null>(null)
  const [autoEnabled, setAutoEnabled] = useState(true)

  const onTick = useCallback(() => setReloadSignal((n) => n + 1), [])
  const { tickNow } = useRefreshSchedule(onTick, { active, enabled: autoEnabled, skipInitialFire: true })
  const onLoadingChange = useCallback((loading: boolean) => setRefreshing(loading), [])
  const onLoaded = useCallback(() => setLastUpdatedAt(Date.now()), [])
  const reload = useCallback(() => setReloadSignal((n) => n + 1), [])

  // 停止（保留进度）/ 强制终止 确认态
  const [confirm, setConfirm] = useState<{ kind: "stop" | "kill"; row: StreamingTaskRow } | null>(null)
  const [busy, setBusy] = useState(false)
  // 续跑目标（打开检查点选择对话框）
  const [resumeTarget, setResumeTarget] = useState<{ instanceId: string; taskName: string } | null>(null)

  const runConfirmed = useCallback(async () => {
    if (!confirm) return
    const { kind, row } = confirm
    setBusy(true)
    try {
      const url =
        kind === "stop"
          ? `${API_BASE}/api/ops/streaming-tasks/${row.instanceId}/stop`
          : `${API_BASE}/api/ops/instances/${row.instanceId}/kill`
      const res = await authFetch(url, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: kind === "stop" ? JSON.stringify({}) : undefined,
      })
      const json = (await res.json().catch(() => null)) as ApiResponse<unknown> | null
      if (!json || json.code !== 0) {
        toast.error(json?.message ?? `HTTP ${res.status}`)
        return
      }
      toast.success(kind === "stop" ? t("stopConfirmTitle") : t("forceKillConfirmTitle"))
      setConfirm(null)
      reload()
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "network error")
    } finally {
      setBusy(false)
    }
  }, [confirm, t, reload])

  const columns = useMemo<ColumnDef<StreamingTaskRow>[]>(
    () => [
      {
        key: "taskName",
        header: t("colTask"),
        widthPct: 24,
        cell: (r) => (
          <div className="align-top" title={r.taskName}>
            <div className="truncate font-medium">{r.taskName}</div>
            <div className="truncate font-mono text-xs text-muted-foreground">…{r.instanceId.slice(-8)}</div>
          </div>
        ),
      },
      {
        key: "state",
        header: t("colState"),
        widthPct: 16,
        cell: (r) => {
          const drift = r.state === "RUNNING" && !r.workerOnline && r.externalJobHandlePresent
          return (
            <div className="flex flex-col gap-0.5">
              <Badge variant={STATE_BADGE[r.state] ?? "outline"}>
                {t(STATE_I18N[r.state] ?? "stateRunning")}
              </Badge>
              {r.state === "SUSPENDED" && (
                <span className="text-xs text-warning" title={t("suspendedHint")}>
                  {t("suspendedHint")}
                </span>
              )}
              {drift && (
                <span className="text-xs text-warning" title={t("disconnected")}>
                  {t("disconnected")}
                </span>
              )}
            </div>
          )
        },
      },
      {
        key: "duration",
        header: t("colDuration"),
        widthPct: 12,
        cell: (r) => (
          <span className="tabular-nums text-sm">{humanizeDuration(r.durationSeconds)}</span>
        ),
      },
      {
        key: "lastCheckpoint",
        header: t("colLastCheckpoint"),
        widthPct: 18,
        cell: (r) => {
          const c = r.lastCheckpoint
          if (!c) return <span className="text-muted-foreground">{t("checkpointNone")}</span>
          return (
            <div className="flex flex-col gap-0.5">
              <Badge variant={c.resumable ? "success" : "outline"}>#{c.ordinal}</Badge>
              {c.expired && <span className="text-xs text-muted-foreground">{t("checkpointExpired")}</span>}
            </div>
          )
        },
      },
      {
        key: "restarts",
        header: t("colRestarts"),
        widthPct: 10,
        cell: (r) => (
          <span className="tabular-nums text-sm">{r.businessAttempt + r.infraRedispatchCount}</span>
        ),
      },
      {
        key: "actions",
        header: t("colActions"),
        widthPct: 20,
        cell: (r) => {
          const stoppable = r.state === "RUNNING"
          const killable = !["STOPPED", "SUCCESS", "FAILED", "SKIPPED"].includes(r.state)
          const resumable = r.state === "STOPPED" || r.state === "SUSPENDED"
          return (
            <div className="flex items-center gap-1">
              <Tooltip>
                <TooltipTrigger
                  render={
                    <Button
                      size="icon"
                      variant="ghost"
                      className="size-7"
                      onClick={() => open("instance-log", { instanceId: r.instanceId, taskName: r.taskName })}
                    >
                      <HugeiconsIcon icon={FileViewIcon} className="size-4" />
                    </Button>
                  }
                />
                <TooltipContent>{t("actionLogs")}</TooltipContent>
              </Tooltip>
              {resumable && (
                <Tooltip>
                  <TooltipTrigger
                    render={
                      <Button
                        size="icon"
                        variant="ghost"
                        className="size-7"
                        onClick={() => setResumeTarget({ instanceId: r.instanceId, taskName: r.taskName })}
                      >
                        <HugeiconsIcon icon={RefreshIcon} className="size-4" />
                      </Button>
                    }
                  />
                  <TooltipContent>{t("actionResume")}</TooltipContent>
                </Tooltip>
              )}
              {stoppable && (
                <Tooltip>
                  <TooltipTrigger
                    render={
                      <Button
                        size="icon"
                        variant="ghost"
                        className="size-7"
                        onClick={() => setConfirm({ kind: "stop", row: r })}
                      >
                        <HugeiconsIcon icon={StopIcon} className="size-4" />
                      </Button>
                    }
                  />
                  <TooltipContent>{t("actionStopGraceful")}</TooltipContent>
                </Tooltip>
              )}
              {killable && (
                <Tooltip>
                  <TooltipTrigger
                    render={
                      <Button
                        size="icon"
                        variant="ghost"
                        className="size-7 text-destructive"
                        onClick={() => setConfirm({ kind: "kill", row: r })}
                      >
                        <HugeiconsIcon icon={Cancel01Icon} className="size-4" />
                      </Button>
                    }
                  />
                  <TooltipContent>{t("actionForceKill")}</TooltipContent>
                </Tooltip>
              )}
            </div>
          )
        },
      },
    ],
    [t, open],
  )

  const fetcher = async (query: FetchQuery): Promise<PageResult<StreamingTaskRow>> => {
    const qs = toQueryParams(query, [])
    qs.set("projectId", String(projectId))
    const res = await authFetch(`${API_BASE}/api/ops/streaming-tasks?${qs.toString()}`)
    if (!res.ok) {
      const errorBody = (await res.json().catch(() => null)) as ApiResponse<unknown> | null
      toast.error(errorBody?.message ?? `HTTP ${res.status}`)
      return { items: [], total: 0, page: query.page, size: query.size }
    }
    const json = (await res.json()) as ApiResponse<unknown>
    if (json.code !== 0 || !json.data) return { items: [], total: 0, page: query.page, size: query.size }
    const o = json.data as Record<string, unknown>
    if (Array.isArray(o.content)) {
      return {
        items: o.content as StreamingTaskRow[],
        total: (o.totalElements as number) ?? (o.content as unknown[]).length,
        page: ((o.number as number) ?? 0) + 1,
        size: (o.size as number) ?? query.size,
      }
    }
    if (Array.isArray(o.items)) {
      return {
        items: o.items as StreamingTaskRow[],
        total: (o.total as number) ?? (o.items as unknown[]).length,
        page: (o.page as number) ?? query.page,
        size: (o.size as number) ?? query.size,
      }
    }
    return { items: [], total: 0, page: query.page, size: query.size }
  }

  return (
    <div className="flex min-h-0 min-w-0 flex-1 flex-col p-5">
      <DataTable<StreamingTaskRow>
        columns={columns}
        getRowId={(r) => r.instanceId}
        mode="server"
        fetcher={fetcher}
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
        emptyIcon={Activity01Icon}
        emptyTitle={t("panelTitle")}
        emptyHint={t("empty")}
      />
      <ConfirmDialog
        open={confirm !== null}
        onOpenChange={(o) => !o && setConfirm(null)}
        title={confirm?.kind === "kill" ? t("forceKillConfirmTitle") : t("stopConfirmTitle")}
        description={confirm?.kind === "kill" ? t("forceKillConfirmDesc") : t("stopConfirmDesc")}
        confirmLabel={confirm?.kind === "kill" ? t("actionForceKill") : t("actionStopGraceful")}
        destructive={confirm?.kind === "kill"}
        busy={busy}
        onConfirm={runConfirmed}
      />
      <ResumeCheckpointDialog
        instanceId={resumeTarget?.instanceId ?? null}
        taskName={resumeTarget?.taskName}
        open={resumeTarget !== null}
        onOpenChange={(o) => !o && setResumeTarget(null)}
        onResumed={reload}
      />
    </div>
  )
}
