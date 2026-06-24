"use client"

/**
 * 周期任务 Tab：列出周期任务，提供冻结 / 解冻开关。
 * 冻结 = 调度器跳过 claim（D5），不删不停；在途实例不受影响。
 * POST /api/ops/tasks/{id}/freeze body { frozen: boolean }
 */

import { useCallback, useEffect, useState } from "react"
import { useLocale, useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import { SnowIcon, BoxIcon } from "@hugeicons/core-free-icons"
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
import { authFetch, API_BASE, type ApiResponse, type TaskDef } from "@/lib/types"

interface FreezeResponse {
  code: number
  data?: TaskDef | null
  outcome: "EXECUTED" | "PENDING_APPROVAL" | "REJECTED"
  message?: string
}

export function PeriodicTasksPanel() {
  const t = useTranslations("ops")
  const locale = useLocale()
  const [tasks, setTasks] = useState<TaskDef[]>([])
  const [loading, setLoading] = useState(true)
  const [busyId, setBusyId] = useState<number | null>(null)

  const fetchTasks = useCallback(async () => {
    setLoading(true)
    try {
      const res = await authFetch(`${API_BASE}/api/ops/tasks`)
      if (!res.ok) return
      const json = (await res.json()) as ApiResponse<TaskDef[]>
      if (json.code === 0 && json.data) setTasks(json.data)
    } catch {
      /* ignore */
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    fetchTasks()
  }, [fetchTasks])

  async function toggleFrozen(task: TaskDef) {
    // 兼容现有 TaskDef：frozen 可能未在类型里；运行时读 unknown 字段
    const currentFrozen = (task as unknown as { frozen?: boolean }).frozen ?? false
    const nextFrozen = !currentFrozen
    setBusyId(task.id)
    try {
      const res = await authFetch(`${API_BASE}/api/ops/tasks/${task.id}/freeze`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ frozen: nextFrozen }),
      })
      const json = (await res.json().catch(() => null)) as FreezeResponse | null
      if (!json || json.code !== 0) {
        toast.error(t("freezeFailed", { msg: json?.message ?? `HTTP ${res.status}` }))
        return
      }
      // ★ 按 outcome 三态分流
      if (json.outcome === "PENDING_APPROVAL") {
        toast.info(`${nextFrozen ? t("tasksFreeze") : t("tasksUnfreeze")} · ${t("outcomePendingApproval")}`)
      } else if (json.outcome === "REJECTED") {
        toast.error(`${nextFrozen ? t("tasksFreeze") : t("tasksUnfreeze")} · ${t("outcomeRejected")}`)
      } else {
        toast.success(nextFrozen ? t("freezeSuccess") : t("unfreezeSuccess"))
        // 乐观更新：本地翻转 frozen 状态
        setTasks((prev) =>
          prev.map((t) =>
            t.id === task.id
              ? ({ ...t, frozen: nextFrozen } as unknown as TaskDef)
              : t,
          ),
        )
      }
    } catch (e) {
      toast.error(t("freezeFailed", { msg: e instanceof Error ? e.message : t("networkError") }))
    } finally {
      setBusyId(null)
    }
  }

  return (
    <DwScroll className="flex-1" innerClassName="flex flex-col gap-3 p-5">
      <div className="flex items-center gap-2">
        <HugeiconsIcon icon={SnowIcon} className="size-4 text-primary" />
        <h3 className="text-sm font-semibold tracking-tight">{t("tasksTitle")}</h3>
        <span className="rounded-md bg-muted px-2 py-0.5 font-mono text-xs text-muted-foreground tabular-nums">
          {tasks.length}
        </span>
      </div>

      {tasks.length === 0 && !loading ? (
        <div className="flex flex-1 flex-col items-center justify-center gap-3 py-20 text-center">
          <div className="flex size-12 items-center justify-center rounded-xl bg-muted text-muted-foreground">
            <HugeiconsIcon icon={BoxIcon} className="size-6" />
          </div>
          <p className="text-sm text-muted-foreground">{t("tasksEmpty")}</p>
        </div>
      ) : (
        <div className="font-sans">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("colTaskName")}</TableHead>
                <TableHead className="w-24">{t("colStatus")}</TableHead>
                <TableHead className="w-24">{t("colFrozen")}</TableHead>
                <TableHead className="w-28 text-right">{t("colActions")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {tasks.map((task) => {
                const frozen = (task as unknown as { frozen?: boolean }).frozen ?? false
                const busy = busyId === task.id
                return (
                  <TableRow key={task.id}>
                    <TableCell className="max-w-0 truncate">
                      <div className="truncate font-medium">{task.name}</div>
                      {task.description && (
                        <div className="truncate text-xs text-muted-foreground">{task.description}</div>
                      )}
                    </TableCell>
                    <TableCell>
                      <Badge variant={task.status === "ONLINE" ? "success" : "outline"}>
                        {task.status === "ONLINE" ? t("statusOnline") : t("statusDraft")}
                      </Badge>
                    </TableCell>
                    <TableCell>
                      <Badge variant={frozen ? "info" : "outline"}>
                        {frozen ? t("tasksFrozen") : t("tasksActive")}
                      </Badge>
                    </TableCell>
                    <TableCell className="text-right">
                      <Button
                        variant="outline"
                        size="sm"
                        className="h-7 text-xs"
                        disabled={busy}
                        onClick={() => toggleFrozen(task)}
                      >
                        <HugeiconsIcon icon={SnowIcon} className="size-3.5" />
                        {frozen ? t("tasksUnfreeze") : t("tasksFreeze")}
                      </Button>
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
