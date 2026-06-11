"use client"

import { HugeiconsIcon } from "@hugeicons/react"
import { RefreshIcon } from "@hugeicons/core-free-icons"

import { Badge } from "@/components/ui/badge"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { formatDateTime, type TaskDef, type TaskInstance } from "@/lib/types"
import { useApi } from "@/lib/workspace/use-api"
import { ViewStatus } from "./view-status"

/** 距今时长的人话表达 */
function ageLabel(iso: string): string {
  const ms = Date.now() - new Date(iso).getTime()
  if (ms < 0) return "刚刚"
  const minutes = Math.floor(ms / 60_000)
  if (minutes < 60) return `${minutes} 分钟前`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} 小时前`
  return `${Math.floor(hours / 24)} 天前`
}

/** 时效分档：>24h 视为陈旧，>6h 提醒 */
function freshnessBadge(iso: string | null) {
  if (!iso) return <Badge variant="destructive">从未成功</Badge>
  const hours = (Date.now() - new Date(iso).getTime()) / 3_600_000
  if (hours > 24) return <Badge variant="destructive">陈旧</Badge>
  if (hours > 6) return <Badge variant="outline">偏旧</Badge>
  return <Badge variant="default">新鲜</Badge>
}

interface Row {
  taskId: number
  name: string
  lastSuccess: string | null
}

/** 数据新鲜度（最小版）：按任务实例最近成功时间推各任务产出时效，时效最差居前 */
export function FreshnessView() {
  const instances = useApi<TaskInstance[]>("/api/ops/instances")
  const tasks = useApi<TaskDef[]>("/api/ops/tasks")

  if (!tasks.data) return <ViewStatus loading={tasks.loading || instances.loading} />

  const lastSuccessByTask = new Map<number, string>()
  for (const inst of instances.data ?? []) {
    if (inst.state !== "SUCCESS" || !inst.finishedAt) continue
    const cur = lastSuccessByTask.get(inst.taskId)
    if (!cur || inst.finishedAt > cur) lastSuccessByTask.set(inst.taskId, inst.finishedAt)
  }

  const rows: Row[] = tasks.data
    .map((t) => ({
      taskId: t.id,
      name: t.name,
      lastSuccess: lastSuccessByTask.get(t.id) ?? null,
    }))
    // 最久未更新居前：从未成功 > 最旧成功时间
    .sort((a, b) => {
      if (a.lastSuccess === null) return b.lastSuccess === null ? 0 : -1
      if (b.lastSuccess === null) return 1
      return a.lastSuccess < b.lastSuccess ? -1 : 1
    })

  return (
    <div className="flex flex-1 flex-col gap-6 overflow-auto p-6 md:p-10">
      <div className="flex flex-col gap-1">
        <div className="flex items-center gap-2">
          <HugeiconsIcon icon={RefreshIcon} className="size-5 text-primary" />
          <h1 className="text-2xl font-semibold tracking-tight">数据新鲜度</h1>
        </div>
        <p className="text-sm text-muted-foreground">
          各任务产出的更新时效，最久未更新居前
        </p>
      </div>

      {rows.length === 0 ? (
        <div className="flex flex-1 items-center justify-center p-10 text-center">
          <p className="text-muted-foreground">暂无任务，先通过左侧 Agent 创建任务</p>
        </div>
      ) : (
        <div className="font-sans">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="w-24 font-mono">任务#</TableHead>
                <TableHead>任务名</TableHead>
                <TableHead className="w-24">时效</TableHead>
                <TableHead className="w-44">最近成功</TableHead>
                <TableHead className="w-32">距今</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((row) => (
                <TableRow key={row.taskId}>
                  <TableCell className="font-mono tabular-nums">{row.taskId}</TableCell>
                  <TableCell>{row.name}</TableCell>
                  <TableCell>{freshnessBadge(row.lastSuccess)}</TableCell>
                  <TableCell className="tabular-nums text-muted-foreground">
                    {formatDateTime(row.lastSuccess)}
                  </TableCell>
                  <TableCell className="text-muted-foreground">
                    {row.lastSuccess ? ageLabel(row.lastSuccess) : "—"}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
    </div>
  )
}
