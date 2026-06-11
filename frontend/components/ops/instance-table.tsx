import Link from "next/link"
import { HugeiconsIcon } from "@hugeicons/react"
import { BoxIcon } from "@hugeicons/core-free-icons"

import { Badge } from "@/components/ui/badge"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { type TaskInstance, formatDateTime } from "@/lib/types"

function stateBadge(state: string) {
  switch (state) {
    case "SUCCESS":
      return <Badge variant="success">成功</Badge>
    case "FAILED":
      return <Badge variant="destructive">失败</Badge>
    case "RUNNING":
      return <Badge variant="info">运行中</Badge>
    default:
      return (
        <Badge variant="outline" className="text-muted-foreground">
          {state}
        </Badge>
      )
  }
}

export function InstanceTable({ instances }: { instances: TaskInstance[] }) {
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
          </TableRow>
        </TableHeader>
        <TableBody>
          {instances.map((inst) => (
            <TableRow key={inst.id}>
              <TableCell className="font-mono tabular-nums">
                {inst.id}
              </TableCell>
              <TableCell className="font-mono tabular-nums">
                {inst.taskId}
              </TableCell>
              <TableCell>
                {inst.state === "FAILED" ? (
                  <Link
                    href={`/diagnosis?instanceId=${inst.id}`}
                    className="inline-block"
                  >
                    {stateBadge(inst.state)}
                  </Link>
                ) : (
                  stateBadge(inst.state)
                )}
              </TableCell>
              <TableCell className="font-mono text-xs">
                {inst.workerNodeCode ?? "—"}
              </TableCell>
              <TableCell className="tabular-nums">
                {formatDateTime(inst.startedAt)}
              </TableCell>
              <TableCell className="tabular-nums">
                {formatDateTime(inst.finishedAt)}
              </TableCell>
              <TableCell className="text-right tabular-nums">
                {inst.attempt}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  )
}
