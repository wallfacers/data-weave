"use client"

/**
 * 手动任务流列表 Tab（ops-center-publish-boundary）：与周期任务流同级的运维主体，统一 DataTable 渲染。
 * 仅展示已发布(ONLINE) 且 schedule_type=MANUAL 的工作流（GET /api/ops/manual-workflows，server 分页+筛选）。
 * 筛选：名称搜索 + 最近触发结果。「运行一次」= POST /api/workflows/{id}/run，按 outcome 三态分流。
 */

import { useMemo, useState, useCallback } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import { PlayIcon, Share08Icon } from "@hugeicons/core-free-icons"
import { toast } from "sonner"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { DataTable } from "@/components/ui/data-table"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import { type ColumnDef, type FilterDef } from "@/lib/data-table"
import { useFormatDateTime } from "@/hooks/use-format-date-time"
import { DagViewerDialog } from "@/components/workspace/dag-viewer-dialog"
import { useRefreshSchedule } from "@/lib/workspace/use-api"
import { ViewRefreshControl } from "../view-refresh-control"
import { yesterdayBizDate } from "@/lib/workspace/biz-date"
import { authFetch, API_BASE } from "@/lib/types"
import { type WorkflowRow, fetchWorkflowPage, recentResultBadge, PriorityCell } from "./periodic-workflows-panel"

interface RunResponse {
  code: number
  data?: { outcome?: "EXECUTED" | "PENDING_APPROVAL" | "REJECTED"; message?: string } | null
  message?: string
}

export function ManualWorkflowsPanel() {
  const t = useTranslations("ops")
  const formatDateTime = useFormatDateTime()
  const [busyId, setBusyId] = useState<number | null>(null)
  const [dagWorkflow, setDagWorkflow] = useState<WorkflowRow | null>(null)

  // 自动刷新
  const [reloadSignal, setReloadSignal] = useState(0)
  const [refreshing, setRefreshing] = useState(false)
  const [lastUpdatedAt, setLastUpdatedAt] = useState<number | null>(null)
  const [autoEnabled, setAutoEnabled] = useState(true)

  const onTick = useCallback(() => setReloadSignal((n) => n + 1), [])
  const { tickNow } = useRefreshSchedule(onTick, { active: true, enabled: autoEnabled, skipInitialFire: true })
  const onLoadingChange = useCallback((loading: boolean) => setRefreshing(loading), [])
  const onLoaded = useCallback(() => setLastUpdatedAt(Date.now()), [])

  const filters = useMemo<FilterDef[]>(
    () => [
      { key: "keyword", label: t("filterWfName"), kind: "search", width: "w-56", placeholder: t("filterWfName") },
      {
        key: "recentResult",
        label: t("filterRecentResult"),
        kind: "select",
        width: "w-36",
        options: [
          { value: "SUCCESS", label: t("stateSuccess") },
          { value: "FAILED", label: t("stateFailed") },
          { value: "NEVER", label: t("recentNever") },
        ],
      },
      {
        key: "priorityTier",
        label: t("filterPriority"),
        kind: "segmented",
        options: [
          { value: "high", label: t("priorityTierHigh") },
          { value: "normal", label: t("priorityTierNormal") },
        ],
      },
    ],
    [t],
  )

  async function runOnce(wf: WorkflowRow) {
    setBusyId(wf.id)
    try {
      const res = await authFetch(`${API_BASE}/api/workflows/${wf.id}/run`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ bizDate: yesterdayBizDate(), scope: "FULL", targetNodeKey: null }),
      })
      const json = (await res.json().catch(() => null)) as RunResponse | null
      if (!json || json.code !== 0) {
        toast.error(t("runFailed", { msg: json?.message ?? `HTTP ${res.status}` }))
        return
      }
      const outcome = json.data?.outcome ?? "EXECUTED"
      if (outcome === "PENDING_APPROVAL") {
        toast.info(`${t("runOnce")} · ${t("outcomePendingApproval")}`)
      } else if (outcome === "REJECTED") {
        toast.error(`${t("runOnce")} · ${t("outcomeRejected")}`)
      } else {
        toast.success(t("runOnceSuccess", { name: wf.name }))
      }
    } catch (e) {
      toast.error(t("runFailed", { msg: e instanceof Error ? e.message : t("networkError") }))
    } finally {
      setBusyId(null)
    }
  }

  const columns = useMemo<ColumnDef<WorkflowRow>[]>(
    () => [
      {
        key: "name",
        header: t("colWorkflowName"),
        widthPct: 14,
        cell: (w) => (
          <Tooltip>
            <TooltipTrigger render={<div className="truncate font-medium">{w.name}</div>} />
            <TooltipContent>{w.name}</TooltipContent>
          </Tooltip>
        ),
      },
      {
        key: "description",
        header: t("colDescription"),
        widthPct: 16,
        cell: (w) =>
          w.description ? (
            <Tooltip>
              <TooltipTrigger render={<div className="truncate text-xs text-muted-foreground">{w.description}</div>} />
              <TooltipContent className="max-w-sm">{w.description}</TooltipContent>
            </Tooltip>
          ) : (
            <span className="text-xs text-muted-foreground">—</span>
          ),
      },
      {
        key: "recentTriggerResult",
        header: t("colRecentResult"),
        widthPct: 11,
        cell: (w) => recentResultBadge(w.recentTriggerResult, t),
      },
      {
        key: "status",
        header: t("colStatus"),
        widthPct: 8,
        cell: () => <Badge variant="success">{t("statusOnline")}</Badge>,
      },
      {
        key: "lastFireTime",
        header: t("colLastFireTime"),
        widthPct: 17,
        cellClassName: "font-mono text-xs tabular-nums text-muted-foreground",
        cell: (w) => formatDateTime(w.lastFireTime),
      },
      {
        key: "priority",
        header: t("colPriority"),
        widthPct: 10,
        align: "right",
        sortable: true,
        cellClassName: "font-mono text-xs tabular-nums",
        cell: (w) => <PriorityCell priority={w.priority} t={t} />,
      },
      {
        key: "currentVersionNo",
        header: t("colVersion"),
        widthPct: 8,
        align: "right",
        cellClassName: "font-mono text-xs tabular-nums",
        cell: (w) => (
          <span>
            v{w.currentVersionNo ?? 0}
            {w.hasDraftChange === 1 && (
              <Badge variant="outline" className="ml-1 h-4 border-amber-500/50 px-1 text-[10px] leading-none text-amber-500">
                {t("draftChange")}
              </Badge>
            )}
          </span>
        ),
      },
      {
        key: "actions",
        header: t("colActions"),
        widthPct: 24,
        cell: (w) => (
          <div className="flex items-center gap-0.5" onClick={(e) => e.stopPropagation()}>
            <Tooltip>
              <TooltipTrigger
                render={
                  <Button
                    size="icon"
                    variant="ghost"
                    className="size-7"
                    disabled={busyId === w.id}
                    onClick={() => runOnce(w)}
                  >
                    <HugeiconsIcon icon={PlayIcon} className="size-4" />
                  </Button>
                }
              />
              <TooltipContent>{t("runOnce")}</TooltipContent>
            </Tooltip>
            {w.status === "ONLINE" && (
              <Tooltip>
                <TooltipTrigger
                  render={
                    <Button
                      size="icon"
                      variant="ghost"
                      className="size-7"
                      onClick={() => setDagWorkflow(w)}
                    >
                      <HugeiconsIcon icon={Share08Icon} className="size-4" />
                    </Button>
                  }
                />
                <TooltipContent>{t("viewDag")}</TooltipContent>
              </Tooltip>
            )}
          </div>
        ),
      },
    ],
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [t, formatDateTime, busyId],
  )

  return (
    <>
      <div className="flex min-h-0 min-w-0 flex-1 flex-col p-5">
        <DataTable<WorkflowRow>
          columns={columns}
          getRowId={(w) => String(w.id)}
          mode="server"
          fetcher={(q) => fetchWorkflowPage("manual-workflows", q, filters)}
          filters={filters}
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
          emptyTitle={t("manualWfEmpty")}
          emptyHint={t("manualWfEmptyHint")}
        />
      </div>
      {dagWorkflow && (
        <DagViewerDialog
          workflowId={dagWorkflow.id}
          workflowName={dagWorkflow.name}
          open={!!dagWorkflow}
          onOpenChange={(v) => { if (!v) setDagWorkflow(null) }}
        />
      )}
    </>
  )
}
