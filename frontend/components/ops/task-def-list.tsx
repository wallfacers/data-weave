"use client"

import { useState } from "react"
import { HugeiconsIcon } from "@hugeicons/react"
import { CheckListIcon, BoxIcon, MoreVerticalIcon } from "@hugeicons/core-free-icons"

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
import { type TaskDef, formatDateTime, API_BASE } from "@/lib/types"

interface TaskDefListProps {
  tasks: TaskDef[]
  total: number
  page: number
  pageSize: number
  onPageChange: (page: number) => void
  onEdit: (task: TaskDef) => void
  onRefresh: () => void
}

function statusBadge(status: string) {
  if (status === "ONLINE") return <Badge variant="success">在线</Badge>
  return <Badge variant="outline" className="text-muted-foreground">草稿</Badge>
}

export function TaskDefList({ tasks, total, page, pageSize, onPageChange, onEdit, onRefresh }: TaskDefListProps) {
  const [menuOpen, setMenuOpen] = useState<number | null>(null)
  const totalPages = Math.max(1, Math.ceil(total / pageSize))

  async function handleAction(task: TaskDef, action: string) {
    setMenuOpen(null)
    try {
      if (action === "publish") {
        await fetch(`${API_BASE}/api/tasks/${task.id}/publish`, { method: "POST" })
      } else if (action === "offline") {
        await fetch(`${API_BASE}/api/tasks/${task.id}/offline`, { method: "POST" })
      } else if (action === "delete") {
        await fetch(`${API_BASE}/api/tasks/${task.id}`, { method: "DELETE" })
      } else if (action === "edit") {
        onEdit(task)
        return
      }
      onRefresh()
    } catch { /* ignore */ }
  }

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center gap-2">
        <HugeiconsIcon icon={CheckListIcon} className="text-primary" />
        <h2 className="text-sm font-medium">任务定义</h2>
        <span className="rounded-md bg-muted px-2 py-0.5 font-mono text-xs text-muted-foreground">
          {total}
        </span>
      </div>

      {tasks.length === 0 ? (
        <div className="flex flex-col items-center justify-center gap-3 py-16 text-center">
          <div className="flex size-12 items-center justify-center rounded-xl bg-muted text-muted-foreground">
            <HugeiconsIcon icon={BoxIcon} className="size-6" />
          </div>
          <p className="text-sm text-muted-foreground">暂无任务定义</p>
          <p className="max-w-sm text-xs text-muted-foreground">
            点击「新建任务」创建草稿，或通过 Agent 对话创建。
          </p>
        </div>
      ) : (
        <>
          <div className="font-sans">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>名称</TableHead>
                  <TableHead className="w-20">类型</TableHead>
                  <TableHead className="w-20">状态</TableHead>
                  <TableHead className="w-14 text-right">优先级</TableHead>
                  <TableHead className="w-16 text-right">版本</TableHead>
                  <TableHead className="w-40">创建时间</TableHead>
                  <TableHead className="w-20 text-right">操作</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {tasks.map((t) => (
                  <TableRow key={t.id}>
                    <TableCell className="font-medium">
                      {t.name}
                      {t.description && (
                        <span className="ml-2 text-xs text-muted-foreground">{t.description}</span>
                      )}
                    </TableCell>
                    <TableCell className="font-mono text-xs">{t.type}</TableCell>
                    <TableCell>{statusBadge(t.status)}</TableCell>
                    <TableCell className="text-right tabular-nums">{t.priority ?? 5}</TableCell>
                    <TableCell className="text-right tabular-nums">v{t.currentVersionNo}</TableCell>
                    <TableCell className="tabular-nums">{formatDateTime(t.createdAt)}</TableCell>
                    <TableCell className="text-right">
                      <div className="relative inline-block">
                        <Button
                          variant="ghost"
                          size="sm"
                          className="size-7 p-0"
                          onClick={() => setMenuOpen(menuOpen === t.id ? null : t.id)}
                        >
                          <HugeiconsIcon icon={MoreVerticalIcon} className="size-4" />
                        </Button>
                        {menuOpen === t.id && (
                          <div className="absolute right-0 z-50 mt-1 w-32 rounded-md border bg-popover p-1 shadow-md">
                            <button className="flex w-full rounded-sm px-2 py-1.5 text-xs hover:bg-accent" onClick={() => handleAction(t, "edit")}>编辑</button>
                            {t.status === "DRAFT" && (
                              <button className="flex w-full rounded-sm px-2 py-1.5 text-xs hover:bg-accent" onClick={() => handleAction(t, "publish")}>发布上线</button>
                            )}
                            {t.status === "ONLINE" && (
                              <button className="flex w-full rounded-sm px-2 py-1.5 text-xs hover:bg-accent" onClick={() => handleAction(t, "offline")}>下线</button>
                            )}
                            {t.status === "DRAFT" && (
                              <button className="flex w-full rounded-sm px-2 py-1.5 text-xs text-destructive hover:bg-accent" onClick={() => handleAction(t, "delete")}>删除</button>
                            )}
                          </div>
                        )}
                      </div>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>

          {totalPages > 1 && (
            <div className="flex items-center justify-between px-1">
              <span className="text-xs text-muted-foreground">
                共 {total} 条，第 {page + 1}/{totalPages} 页
              </span>
              <div className="flex gap-1">
                <Button variant="outline" size="sm" className="h-7 text-xs" disabled={page === 0} onClick={() => onPageChange(page - 1)}>上一页</Button>
                <Button variant="outline" size="sm" className="h-7 text-xs" disabled={page >= totalPages - 1} onClick={() => onPageChange(page + 1)}>下一页</Button>
              </div>
            </div>
          )}
        </>
      )}
    </div>
  )
}
