"use client"

/**
 * 补数据实例 Tab：列出补数据 run，每行显示进度（success/failed/running/total），
 * 支持「下钻」到该 run 下的子实例（复用周期实例面板 + 筛选）。
 */

import { useCallback, useEffect, useState } from "react"
import { useLocale, useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  BoxIcon,
  Loading03Icon,
  Add01Icon,
  ArrowDown02Icon,
} from "@hugeicons/core-free-icons"

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
import { authFetch, API_BASE, formatDateTime, type ApiResponse } from "@/lib/types"
import { BackfillDialog } from "./backfill-dialog"

interface BackfillRun {
  id: string
  targetType: "task" | "workflow"
  targetId: number
  targetName?: string
  dateStart: string
  dateEnd: string
  parallelism: number
  state: "RUNNING" | "SUCCESS" | "FAILED" | "PARTIAL"
  total: number
  success: number
  failed: number
  running: number
  createdAt: string
}

const STATE_VARIANT: Record<string, "success" | "destructive" | "warning" | "info"> = {
  RUNNING: "info",
  SUCCESS: "success",
  FAILED: "destructive",
  PARTIAL: "warning",
}

export function BackfillPanel() {
  const t = useTranslations("ops")
  const locale = useLocale()
  const [runs, setRuns] = useState<BackfillRun[]>([])
  const [loading, setLoading] = useState(true)
  const [dialogOpen, setDialogOpen] = useState(false)
  const [drillRunId, setDrillRunId] = useState<string | null>(null)

  const fetchRuns = useCallback(async () => {
    setLoading(true)
    try {
      const res = await authFetch(`${API_BASE}/api/ops/backfill?page=0&size=50`)
      if (!res.ok) return
      const json = (await res.json()) as ApiResponse<{ items: BackfillRun[] }>
      if (json.code === 0 && json.data?.items) setRuns(json.data.items)
    } catch {
      /* ignore */
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    fetchRuns()
  }, [fetchRuns])

  function stateBadge(state: string) {
    const keyMap: Record<string, string> = {
      RUNNING: "backfillStateRunning",
      SUCCESS: "backfillStateSuccess",
      FAILED: "backfillStateFailed",
      PARTIAL: "backfillStatePartial",
    }
    const variant = STATE_VARIANT[state] ?? "info"
    return <Badge variant={variant}>{t(keyMap[state] as never)}</Badge>
  }

  return (
    <DwScroll className="flex-1" innerClassName="flex flex-col gap-3 p-5">
      <div className="flex items-center gap-2">
        <HugeiconsIcon icon={Loading03Icon} className="size-4 text-primary" />
        <h3 className="text-sm font-semibold tracking-tight">{t("backfillTabTitle")}</h3>
        <div className="flex-1" />
        <Button size="sm" className="h-8" onClick={() => setDialogOpen(true)}>
          <HugeiconsIcon icon={Add01Icon} className="size-4" />
          {t("backfillTrigger")}
        </Button>
      </div>

      {runs.length === 0 && !loading ? (
        <div className="flex flex-1 flex-col items-center justify-center gap-3 py-20 text-center">
          <div className="flex size-12 items-center justify-center rounded-xl bg-muted text-muted-foreground">
            <HugeiconsIcon icon={BoxIcon} className="size-6" />
          </div>
          <p className="text-sm text-muted-foreground">{t("backfillEmpty")}</p>
          <p className="max-w-sm text-xs text-muted-foreground">{t("backfillEmptyHint")}</p>
        </div>
      ) : (
        <div className="font-sans">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="w-24 font-mono">{t("backfillColRun")}</TableHead>
                <TableHead className="w-40">{t("backfillColTarget")}</TableHead>
                <TableHead className="w-44">{t("backfillColDates")}</TableHead>
                <TableHead className="w-20 text-right">{t("backfillColParallelism")}</TableHead>
                <TableHead>{t("backfillColProgress")}</TableHead>
                <TableHead className="w-24">{t("backfillColState")}</TableHead>
                <TableHead className="w-36">{t("backfillColCreatedAt")}</TableHead>
                <TableHead className="w-20 text-right">{t("colActions")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {runs.map((run) => (
                <TableRow key={run.id}>
                  <TableCell className="font-mono tabular-nums text-xs">
                    {run.id.slice(0, 8)}
                  </TableCell>
                  <TableCell>
                    <div className="truncate font-medium" title={run.targetName}>
                      {run.targetName ?? `#${run.targetId}`}
                    </div>
                    <div className="text-xs text-muted-foreground">{run.targetType}</div>
                  </TableCell>
                  <TableCell className="tabular-nums text-xs">
                    {run.dateStart} ~ {run.dateEnd}
                  </TableCell>
                  <TableCell className="text-right tabular-nums">{run.parallelism}</TableCell>
                  <TableCell>
                    <div className="flex items-center gap-2">
                      <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-muted">
                        <div
                          className="h-full bg-success transition-all"
                          style={{ width: `${run.total ? (run.success / run.total) * 100 : 0}%` }}
                        />
                      </div>
                      <span className="text-xs tabular-nums text-muted-foreground">
                        {t("backfillProgress", {
                          success: run.success,
                          failed: run.failed,
                          running: run.running,
                          total: run.total,
                        })}
                      </span>
                    </div>
                  </TableCell>
                  <TableCell>{stateBadge(run.state)}</TableCell>
                  <TableCell className="tabular-nums text-xs">
                    {formatDateTime(run.createdAt, locale)}
                  </TableCell>
                  <TableCell className="text-right">
                    <Button
                      variant="ghost"
                      size="sm"
                      className="h-6 px-2 text-xs"
                      onClick={() => setDrillRunId(run.id === drillRunId ? null : run.id)}
                    >
                      <HugeiconsIcon icon={ArrowDown02Icon} className="size-3.5" />
                      {t("backfillDrill")}
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}

      {drillRunId && (
        <div className="rounded-lg border bg-muted/30 p-3">
          <p className="mb-2 text-xs text-muted-foreground">
            {t("backfillDrill")}: {drillRunId.slice(0, 8)}
          </p>
          <p className="text-sm text-muted-foreground">
            {/* 下钻：复用周期实例面板，按 runId 过滤（契约① 预留） */}
            Drill-down list placeholder — backend integration provides GET /backfill/{"{runId}"}.
          </p>
        </div>
      )}

      <BackfillDialog
        open={dialogOpen}
        onOpenChange={setDialogOpen}
        onSuccess={fetchRuns}
      />
    </DwScroll>
  )
}
