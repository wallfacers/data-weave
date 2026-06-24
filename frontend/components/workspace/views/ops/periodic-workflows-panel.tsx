"use client"

/**
 * 周期任务流列表 Tab（ops-center-publish-boundary）：运维主体。
 * 仅展示已发布(ONLINE) 且 schedule_type=CRON 的工作流（GET /api/ops/periodic-workflows）。
 * 任务与任务流概念分离：这里不再列任务，也不提供任务级 freeze；冻结改为节点级——
 * 点「查看 DAG」进入画布/实例视图，在 DAG 里对任意节点冻结/解冻。
 */

import { useCallback, useEffect, useState } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import { Calendar03Icon, BoxIcon, Share08Icon } from "@hugeicons/core-free-icons"

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
import { authFetch, API_BASE, type ApiResponse, type WorkflowDef } from "@/lib/types"

export function PeriodicWorkflowsPanel() {
  const t = useTranslations("ops")
  const open = useWorkspaceStore((s) => s.open)
  const [workflows, setWorkflows] = useState<WorkflowDef[]>([])
  const [loading, setLoading] = useState(true)

  const fetchWorkflows = useCallback(async () => {
    setLoading(true)
    try {
      const res = await authFetch(`${API_BASE}/api/ops/periodic-workflows`)
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

  return (
    <DwScroll className="flex-1" innerClassName="flex flex-col gap-3 p-5">
      <div className="flex items-center gap-2">
        <HugeiconsIcon icon={Calendar03Icon} className="size-4 text-primary" />
        <h3 className="text-sm font-semibold tracking-tight">{t("periodicWfTitle")}</h3>
        <span className="rounded-md bg-muted px-2 py-0.5 font-mono text-xs text-muted-foreground tabular-nums">
          {workflows.length}
        </span>
      </div>

      {workflows.length === 0 && !loading ? (
        <div className="flex flex-1 flex-col items-center justify-center gap-3 py-20 text-center">
          <div className="flex size-12 items-center justify-center rounded-xl bg-muted text-muted-foreground">
            <HugeiconsIcon icon={BoxIcon} className="size-6" />
          </div>
          <p className="text-sm text-muted-foreground">{t("periodicWfEmpty")}</p>
          <p className="max-w-sm text-xs text-muted-foreground">{t("periodicWfEmptyHint")}</p>
        </div>
      ) : (
        <div className="font-sans">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("colWorkflowName")}</TableHead>
                <TableHead className="w-44">{t("colCron")}</TableHead>
                <TableHead className="w-24">{t("colStatus")}</TableHead>
                <TableHead className="w-20 text-right">{t("colVersion")}</TableHead>
                <TableHead className="w-28 text-right">{t("colActions")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {workflows.map((wf) => (
                <TableRow key={wf.id}>
                  <TableCell className="max-w-0 truncate">
                    <div className="truncate font-medium">{wf.name}</div>
                    {wf.description && (
                      <div className="truncate text-xs text-muted-foreground">{wf.description}</div>
                    )}
                  </TableCell>
                  <TableCell>
                    <span className="font-mono text-xs text-muted-foreground">{wf.cron ?? "—"}</span>
                  </TableCell>
                  <TableCell>
                    <Badge variant="success">{t("statusOnline")}</Badge>
                  </TableCell>
                  <TableCell className="text-right font-mono text-xs tabular-nums">
                    v{wf.currentVersionNo}
                  </TableCell>
                  <TableCell className="text-right">
                    <Button
                      variant="outline"
                      size="sm"
                      className="h-7 text-xs"
                      onClick={() => open("workflow-canvas", { workflowId: wf.id, name: wf.name })}
                    >
                      <HugeiconsIcon icon={Share08Icon} className="size-3.5" />
                      {t("viewDag")}
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
    </DwScroll>
  )
}
