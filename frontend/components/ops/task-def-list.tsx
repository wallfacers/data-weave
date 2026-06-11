import { HugeiconsIcon } from "@hugeicons/react"
import { CheckListIcon, BoxIcon } from "@hugeicons/core-free-icons"

import { Badge } from "@/components/ui/badge"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { type TaskDef, formatDateTime } from "@/lib/types"

export function TaskDefList({ tasks }: { tasks: TaskDef[] }) {
  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center gap-2">
        <HugeiconsIcon icon={CheckListIcon} className="text-primary" />
        <h2 className="text-sm font-medium">任务定义列表</h2>
        <span className="rounded-md bg-muted px-2 py-0.5 font-mono text-xs text-muted-foreground">
          {tasks.length}
        </span>
      </div>

      {tasks.length === 0 ? (
        <div className="flex flex-col items-center justify-center gap-3 py-16 text-center">
          <div className="flex size-12 items-center justify-center rounded-xl bg-muted text-muted-foreground">
            <HugeiconsIcon icon={BoxIcon} className="size-6" />
          </div>
          <p className="text-sm text-muted-foreground">暂无任务定义</p>
          <p className="max-w-sm text-xs text-muted-foreground">
            通过 Agent 对话创建任务后，任务定义将在此展示。
          </p>
        </div>
      ) : (
        <div className="font-sans">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>名称</TableHead>
                <TableHead className="w-20">类型</TableHead>
                <TableHead className="w-24">状态</TableHead>
                <TableHead className="w-16 text-right">版本</TableHead>
                <TableHead className="w-40">创建时间</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {tasks.map((t) => (
                <TableRow key={t.id}>
                  <TableCell className="font-medium">{t.name}</TableCell>
                  <TableCell className="font-mono text-xs">{t.type}</TableCell>
                  <TableCell>
                    {t.status === "ONLINE" ? (
                      <Badge variant="success">在线</Badge>
                    ) : (
                      <Badge variant="outline" className="text-muted-foreground">
                        下线
                      </Badge>
                    )}
                  </TableCell>
                  <TableCell className="text-right tabular-nums">
                    v{t.currentVersionNo}
                  </TableCell>
                  <TableCell className="tabular-nums">
                    {formatDateTime(t.createdAt)}
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
