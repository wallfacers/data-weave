"use client"

/**
 * 周期任务流列表 Tab（ops-center-publish-boundary）：运维主体，统一 DataTable 渲染。
 * 仅展示已发布(ONLINE) 且 schedule_type=CRON 的工作流（GET /api/ops/periodic-workflows，server 分页+筛选）。
 * 筛选：名称搜索 + 草稿改动段控 + 最近触发结果；预设：有未发布改动 / 最近触发失败。
 * 冻结改为节点级——点「查看 DAG」进入画布对节点冻结/解冻。
 * 行操作含「触发补数据」（预填 workflow 目标）；补数据实例可在补数据实例 Tab 查看。
 */

import { useMemo, useState, useCallback } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import { Share08Icon, Calendar03Icon } from "@hugeicons/core-free-icons"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { DataTable } from "@/components/ui/data-table"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import {
  type ColumnDef,
  type FilterDef,
  type FilterPreset,
  type FetchQuery,
  type PageResult,
  toQueryParams,
} from "@/lib/data-table"
import { useFormatDateTime } from "@/hooks/use-format-date-time"
import { authFetch, API_BASE, type ApiResponse } from "@/lib/types"
import { currentProjectId } from "@/lib/project-context"
import { useRefreshSchedule } from "@/lib/workspace/use-api"
import { ViewRefreshControl } from "../view-refresh-control"
import { DagViewerDialog } from "@/components/workspace/dag-viewer-dialog"
import { BackfillDialog } from "@/components/workspace/views/ops/backfill-dialog"

/** 后端 WorkflowListRow 投影（GET /api/ops/periodic-workflows） */
export interface WorkflowRow {
  id: number
  name: string
  description: string | null
  cron: string | null
  status: string
  currentVersionNo: number | null
  hasDraftChange: number | null
  lastFireTime: string | null
  priority: number | null
  timeoutSec: number | null
  updatedAt: string | null
  updatedBy: number | null
  catalogNodeId: number | null
  recentTriggerResult: string | null
}

/** server 取数：拼 query 打 /api/ops/{path}，归一 Page 信封 */
export async function fetchWorkflowPage(
  path: string,
  query: FetchQuery,
  filters: FilterDef[],
): Promise<PageResult<WorkflowRow>> {
  const qs = toQueryParams(query, filters)
  qs.set("projectId", String(currentProjectId()))
  const res = await authFetch(`${API_BASE}/api/ops/${path}?${qs.toString()}`)
  const empty = { items: [], total: 0, page: query.page, size: query.size }
  if (!res.ok) return empty
  const json = (await res.json()) as ApiResponse<unknown>
  if (json.code !== 0 || !json.data) return empty
  const o = json.data as Record<string, unknown>
  if (Array.isArray(o.items)) {
    return {
      items: o.items as WorkflowRow[],
      total: (o.total as number) ?? (o.items as unknown[]).length,
      page: (o.page as number) ?? query.page,
      size: (o.size as number) ?? query.size,
    }
  }
  if (Array.isArray(json.data)) {
    const arr = json.data as WorkflowRow[]
    return { items: arr, total: arr.length, page: 1, size: arr.length || query.size }
  }
  return empty
}

/** 最近触发结果 Badge：成功/失败/从未触发 */
export function recentResultBadge(
  result: string | null,
  t: (k: string) => string,
): React.ReactNode {
  if (!result) return <Badge variant="outline" className="text-muted-foreground">{t("recentNever")}</Badge>
  if (result === "SUCCESS") return <Badge variant="success">{t("stateSuccess")}</Badge>
  if (result === "FAILED") return <Badge variant="destructive">{t("stateFailed")}</Badge>
  return <Badge variant="info">{result}</Badge>
}

export function PeriodicWorkflowsPanel() {
  const t = useTranslations("ops")
  const formatDateTime = useFormatDateTime()
  const [dagWorkflow, setDagWorkflow] = useState<WorkflowRow | null>(null)
  const [backfillWorkflow, setBackfillWorkflow] = useState<WorkflowRow | null>(null)

  // 自动刷新
  const [reloadSignal, setReloadSignal] = useState(0)
  const [refreshing, setRefreshing] = useState(false)
  const [lastUpdatedAt, setLastUpdatedAt] = useState<number | null>(null)
  const [autoEnabled, setAutoEnabled] = useState(true)

  const onTick = useCallback(() => setReloadSignal((n) => n + 1), [])
  const { tickNow } = useRefreshSchedule(onTick, { active: true, enabled: autoEnabled })
  const onLoadingChange = useCallback((loading: boolean) => setRefreshing(loading), [])
  const onLoaded = useCallback(() => setLastUpdatedAt(Date.now()), [])

  const filters = useMemo<FilterDef[]>(
    () => [
      { key: "keyword", label: t("filterWfName"), kind: "search", width: "w-56", placeholder: t("filterWfName") },
      {
        key: "hasDraftChange",
        label: t("filterDraft"),
        kind: "segmented",
        options: [
          { value: "1", label: t("draftOnly") },
          { value: "0", label: t("draftNone") },
        ],
      },
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
    ],
    [t],
  )

  const presets = useMemo<FilterPreset[]>(
    () => [
      { key: "draft", label: t("presetDraftChange"), set: { hasDraftChange: "1", keyword: "", recentResult: "" } },
      { key: "recentFailed", label: t("presetRecentFailed"), set: { recentResult: "FAILED", keyword: "", hasDraftChange: "" } },
    ],
    [t],
  )

  const columns = useMemo<ColumnDef<WorkflowRow>[]>(
    () => [
      {
        key: "name",
        header: t("colWorkflowName"),
        widthPct: 26,
        cell: (w) => <div className="truncate font-medium" title={w.name}>{w.name}</div>,
      },
      {
        key: "cron",
        header: t("colCron"),
        widthPct: 16,
        cellClassName: "font-mono text-xs text-muted-foreground",
        cell: (w) => w.cron ?? "—",
      },
      {
        key: "recentTriggerResult",
        header: t("colRecentResult"),
        widthPct: 12,
        cell: (w) => recentResultBadge(w.recentTriggerResult, t),
      },
      {
        key: "status",
        header: t("colStatus"),
        widthPct: 10,
        cell: () => <Badge variant="success">{t("statusOnline")}</Badge>,
      },
      {
        key: "lastFireTime",
        header: t("colLastFireTime"),
        widthPct: 16,
        cellClassName: "font-mono text-xs tabular-nums text-muted-foreground",
        cell: (w) => formatDateTime(w.lastFireTime),
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
        widthPct: 12,
        cell: (w) => {
          if (w.status !== "ONLINE") return null
          return (
            <div className="flex items-center gap-0.5" onClick={(e) => e.stopPropagation()}>
              <Tooltip>
                <TooltipTrigger
                  render={
                    <Button
                      size="icon"
                      variant="ghost"
                      className="size-7"
                      onClick={() => setBackfillWorkflow(w)}
                    >
                      <HugeiconsIcon icon={Calendar03Icon} className="size-4" />
                    </Button>
                  }
                />
                <TooltipContent>{t("backfillTrigger")}</TooltipContent>
              </Tooltip>
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
            </div>
          )
        },
      },
    ],
    [t, formatDateTime],
  )

  return (
    <>
      <div className="flex min-h-0 min-w-0 flex-1 flex-col p-5">
        <DataTable<WorkflowRow>
          columns={columns}
          getRowId={(w) => String(w.id)}
          mode="server"
          fetcher={(q) => fetchWorkflowPage("periodic-workflows", q, filters)}
          filters={filters}
          presets={presets}
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
          emptyTitle={t("periodicWfEmpty")}
          emptyHint={t("periodicWfEmptyHint")}
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
      <BackfillDialog
        open={!!backfillWorkflow}
        onOpenChange={(v) => { if (!v) setBackfillWorkflow(null) }}
        initialTargetType="workflow"
        initialTargetId={backfillWorkflow?.id ?? ""}
        initialTargetName={backfillWorkflow?.name}
      />
    </>
  )
}
