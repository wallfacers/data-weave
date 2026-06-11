"use client"

import { useState } from "react"
import Link from "next/link"
import { HugeiconsIcon } from "@hugeicons/react"
import { BoxIcon } from "@hugeicons/core-free-icons"

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
import { type TaskInstance, formatDateTime, API_BASE, authFetch } from "@/lib/types"
import { useSidePanelStore } from "@/lib/side-panel/store"

function stateBadge(state: string) {
  switch (state) {
    case "SUCCESS":
      return <Badge variant="success">成功</Badge>
    case "FAILED":
      return <Badge variant="destructive">失败</Badge>
    case "RUNNING":
      return <Badge variant="info">运行中</Badge>
    case "PAUSED":
      return <Badge variant="outline" className="text-amber-600">已暂停</Badge>
    case "STOPPED":
      return <Badge variant="outline" className="text-muted-foreground">已终止</Badge>
    default:
      return (
        <Badge variant="outline" className="text-muted-foreground">
          {state}
        </Badge>
      )
  }
}

async function doAction(instanceId: number, action: string) {
  await authFetch(`${API_BASE}/api/ops/instances/${instanceId}/${action}`, { method: "POST" })
}

export function InstanceTable({ instances }: { instances: TaskInstance[] }) {
  const [, setRefresh] = useState(0)
  const sidePanel = useSidePanelStore()

  if (instances.length === 0) {
    return (
      <div className="flex flex-1 flex-col items-center justify-center gap-3 py-20 text-center">
        <div className="flex size-12 items-center justify-center rounded-xl bg-muted text-muted-foreground">
          <HugeiconsIcon icon={BoxIcon} className="size-6" />
        </div>
        <p className="text-sm text-muted-foreground">暂无运行实例</p>
        <p className="max-w-sm text-xs text-muted-foreground">
          任务执行后，运行实例将在此展示。通过 Agent 对话创建并上线任务即可。
        </p>
      </div>
    )
  }

  function handleViewLog(inst: TaskInstance) {
    sidePanel.open("log-viewer", `实例 #${inst.id} 日志`, {
      instanceId: inst.id,
      taskId: inst.taskId,
      startedAt: inst.startedAt,
      finishedAt: inst.finishedAt,
    })
  }

  return (
    <div className="font-sans">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead className="w-24 font-mono">实例#</TableHead>
            <TableHead className="w-20 font-mono">任务#</TableHead>
            <TableHead className="w-24">状态</TableHead>
            <TableHead className="w-28">节点</TableHead>
            <TableHead className="w-40">开始时间</TableHead>
            <TableHead className="w-40">结束时间</TableHead>
            <TableHead className="w-20 text-right">尝试</TableHead>
            <TableHead className="w-40 text-right">操作</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {instances.map((inst) => (
            <TableRow key={inst.id}>
              <TableCell className="font-mono tabular-nums">{inst.id}</TableCell>
              <TableCell className="font-mono tabular-nums">{inst.taskId}</TableCell>
              <TableCell>
                {inst.state === "FAILED" ? (
                  <Link href={`/diagnosis?instanceId=${inst.id}`} className="inline-block">
                    {stateBadge(inst.state)}
                  </Link>
                ) : (
                  stateBadge(inst.state)
                )}
              </TableCell>
              <TableCell className="font-mono text-xs">{inst.workerNodeCode ?? "—"}</TableCell>
              <TableCell className="tabular-nums">{formatDateTime(inst.startedAt)}</TableCell>
              <TableCell className="tabular-nums">{formatDateTime(inst.finishedAt)}</TableCell>
              <TableCell className="text-right tabular-nums">{inst.attempt}</TableCell>
              <TableCell className="text-right">
                <div className="flex justify-end gap-1">
                  {inst.state === "RUNNING" && (
                    <>
                      <Button variant="ghost" size="sm" className="h-6 px-2 text-xs" onClick={() => doAction(inst.id, "pause").then(() => setRefresh(n => n + 1))}>暂停</Button>
                      <Button variant="ghost" size="sm" className="h-6 px-2 text-xs text-destructive" onClick={() => doAction(inst.id, "kill").then(() => setRefresh(n => n + 1))}>终止</Button>
                    </>
                  )}
                  {inst.state === "PAUSED" && (
                    <>
                      <Button variant="ghost" size="sm" className="h-6 px-2 text-xs" onClick={() => doAction(inst.id, "resume").then(() => setRefresh(n => n + 1))}>恢复</Button>
                      <Button variant="ghost" size="sm" className="h-6 px-2 text-xs text-destructive" onClick={() => doAction(inst.id, "kill").then(() => setRefresh(n => n + 1))}>终止</Button>
                    </>
                  )}
                  {(inst.state === "SUCCESS" || inst.state === "FAILED") && (
                    <Button variant="ghost" size="sm" className="h-6 px-2 text-xs" onClick={() => doAction(inst.id, "kill").then(() => setRefresh(n => n + 1))}>重跑</Button>
                  )}
                  <Button variant="ghost" size="sm" className="h-6 px-2 text-xs" onClick={() => handleViewLog(inst)}>日志</Button>
                </div>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  )
}
