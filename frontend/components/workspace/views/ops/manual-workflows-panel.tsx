"use client"

/**
 * 手动任务流列表 Tab（ops-center-publish-boundary）：与周期任务流同级的运维主体。
 * 仅展示已发布(ONLINE) 且 schedule_type=MANUAL 的工作流（GET /api/ops/manual-workflows）。
 * 「运行一次」= 人工触发 POST /api/workflows/{id}/run，按 outcome 三态分流。
 * 注意：手动「触发」是动作；被手动补跑的 CRON 工作流仍归「周期任务流列表」，其实例在「任务流实例」按来源筛。
 */

import { useCallback, useEffect, useState } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import { CursorMagicSelection02Icon, BoxIcon, PlayIcon, Share08Icon } from "@hugeicons/core-free-icons"
import { toast } from "sonner"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { DwScroll } from "@/components/ui/dw-scroll"
import { useWorkspaceStore } from "@/lib/workspace/store"
import { yesterdayBizDate } from "@/lib/workspace/biz-date"
import { authFetch, API_BASE, type ApiResponse, type WorkflowDef } from "@/lib/types"

interface RunResponse {
  code: number
  data?: { outcome?: "EXECUTED" | "PENDING_APPROVAL" | "REJECTED"; message?: string } | null
  message?: string
}

export function ManualWorkflowsPanel() {
  const t = useTranslations("ops")
  const open = useWorkspaceStore((s) => s.open)
  const [workflows, setWorkflows] = useState<WorkflowDef[]>([])
  const [loading, setLoading] = useState(true)
  const [busyId, setBusyId] = useState<number | null>(null)

  const fetchWorkflows = useCallback(async () => {
    setLoading(true)
    try {
      const res = await authFetch(`${API_BASE}/api/ops/manual-workflows`)
      if (!res.ok) return
      const json = (await res.json()) as ApiResponse<WorkflowDef[]>
      if (json.code === 0 && json.data) setWorkflows(json.data)
    } catch {
      /* ignore */
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    fetchWorkflows()
  }, [fetchWorkflows])

  async function runOnce(wf: WorkflowDef) {
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

  return (
    <DwScroll className="flex-1" innerClassName="flex flex-col gap-3 p-5">
      <div className="flex items-center gap-2">
        <HugeiconsIcon icon={CursorMagicSelection02Icon} className="size-4 text-primary" />
        <h3 className="text-sm font-semibold tracking-tight">{t("manualWfTitle")}</h3>
        <span className="rounded-md bg-muted px-2 py-0.5 font-mono text-xs text-muted-foreground tabular-nums">
          {workflows.length}
        </span>
      </div>

      {workflows.length === 0 && !loading ? (
        <div className="flex flex-1 flex-col items-center justify-center gap-3 py-20 text-center">
          <div className="flex size-12 items-center justify-center rounded-xl bg-muted text-muted-foreground">
            <HugeiconsIcon icon={BoxIcon} className="size-6" />
          </div>
          <p className="text-sm text-muted-foreground">{t("manualWfEmpty")}</p>
          <p className="max-w-sm text-xs text-muted-foreground">{t("manualWfEmptyHint")}</p>
        </div>
      ) : (
        <div className="font-sans">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("colWorkflowName")}</TableHead>
                <TableHead className="w-24">{t("colStatus")}</TableHead>
                <TableHead className="w-20 text-right">{t("colVersion")}</TableHead>
                <TableHead className="w-44 text-right">{t("colActions")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {workflows.map((wf) => {
                const busy = busyId === wf.id
                return (
                  <TableRow key={wf.id}>
                    <TableCell className="max-w-0 truncate">
                      <div className="truncate font-medium">{wf.name}</div>
                      {wf.description && (
                        <div className="truncate text-xs text-muted-foreground">{wf.description}</div>
                      )}
                    </TableCell>
                    <TableCell>
                      <Badge variant="success">{t("statusOnline")}</Badge>
                    </TableCell>
                    <TableCell className="text-right font-mono text-xs tabular-nums">
                      v{wf.currentVersionNo}
                    </TableCell>
                    <TableCell className="text-right">
                      <div className="flex items-center justify-end gap-1.5">
                        <Button
                          variant="outline"
                          size="sm"
                          className="h-7 text-xs"
                          onClick={() => open("workflow-canvas", { workflowId: wf.id, name: wf.name })}
                        >
                          <HugeiconsIcon icon={Share08Icon} className="size-3.5" />
                          {t("viewDag")}
                        </Button>
                        <Button
                          size="sm"
                          className="h-7 text-xs"
                          disabled={busy}
                          onClick={() => runOnce(wf)}
                        >
                          <HugeiconsIcon icon={PlayIcon} className="size-3.5" />
                          {t("runOnce")}
                        </Button>
                      </div>
                    </TableCell>
                  </TableRow>
                )
              })}
            </TableBody>
          </Table>
        </div>
      )}
    </DwScroll>
  )
}
