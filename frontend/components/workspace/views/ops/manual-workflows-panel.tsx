"use client"

/**
 * 手动任务流列表 Tab（ops-center-publish-boundary）：与周期任务流同级的运维主体，统一 DataTable 渲染。
 * 仅展示已发布(ONLINE) 且 schedule_type=MANUAL 的工作流（GET /api/ops/manual-workflows，server 分页+筛选）。
 * 筛选：名称搜索 + 最近触发结果。「运行一次」= POST /api/workflows/{id}/run，按 outcome 三态分流。
 */

import { useMemo, useState } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import { CursorMagicSelection02Icon, PlayIcon, Share08Icon } from "@hugeicons/core-free-icons"
import { toast } from "sonner"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { DataTable } from "@/components/ui/data-table"
import { type ColumnDef, type FilterDef } from "@/lib/data-table"
import { useFormatDateTime } from "@/hooks/use-format-date-time"
import { useWorkspaceStore } from "@/lib/workspace/store"
import { yesterdayBizDate } from "@/lib/workspace/biz-date"
import { authFetch, API_BASE } from "@/lib/types"
import { type WorkflowRow, fetchWorkflowPage, recentResultBadge } from "./periodic-workflows-panel"

interface RunResponse {
  code: number
  data?: { outcome?: "EXECUTED" | "PENDING_APPROVAL" | "REJECTED"; message?: string } | null
  message?: string
}

export function ManualWorkflowsPanel() {
  const t = useTranslations("ops")
  const open = useWorkspaceStore((s) => s.open)
  const formatDateTime = useFormatDateTime()
  const [busyId, setBusyId] = useState<number | null>(null)

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
        widthPct: 28,
        cell: (w) => (
          <div title={w.name}>
            <div className="truncate font-medium">{w.name}</div>
            {w.description && <div className="truncate text-xs text-muted-foreground">{w.description}</div>}
          </div>
        ),
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
        widthPct: 18,
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
        widthPct: 24,
        align: "right",
        cell: (w) => (
          <div className="flex items-center justify-end gap-1.5">
            <Button
              variant="outline"
              size="sm"
              className="h-7 text-xs"
              onClick={() => open("workflow-canvas", { workflowId: w.id, name: w.name })}
            >
              <HugeiconsIcon icon={Share08Icon} className="size-3.5" />
              {t("viewDag")}
            </Button>
            <Button size="sm" className="h-7 text-xs" disabled={busyId === w.id} onClick={() => runOnce(w)}>
              <HugeiconsIcon icon={PlayIcon} className="size-3.5" />
              {t("runOnce")}
            </Button>
          </div>
        ),
      },
    ],
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [t, formatDateTime, open, busyId],
  )

  return (
    <div className="flex h-full min-w-0 flex-col gap-3 p-5">
      <div className="flex shrink-0 items-center gap-2">
        <HugeiconsIcon icon={CursorMagicSelection02Icon} className="size-4 text-primary" />
        <h3 className="text-sm font-semibold tracking-tight">{t("manualWfTitle")}</h3>
      </div>
      <DataTable<WorkflowRow>
        columns={columns}
        getRowId={(w) => String(w.id)}
        mode="server"
        fetcher={(q) => fetchWorkflowPage("manual-workflows", q, filters)}
        filters={filters}
        emptyTitle={t("manualWfEmpty")}
        emptyHint={t("manualWfEmptyHint")}
      />
    </div>
  )
}
