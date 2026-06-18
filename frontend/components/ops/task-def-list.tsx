"use client"

import { useState } from "react"
import { useLocale, useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import { CheckListIcon, BoxIcon } from "@hugeicons/core-free-icons"

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
import { type TaskDef, formatDateTime, API_BASE, authFetch } from "@/lib/types"

interface TaskDefListProps {
  tasks: TaskDef[]
  total: number
  page: number
  pageSize: number
  onPageChange: (page: number) => void
  onEdit: (task: TaskDef) => void
  onRefresh: () => void
}

export function TaskDefList({ tasks, total, page, pageSize, onPageChange, onEdit, onRefresh }: TaskDefListProps) {
  const t = useTranslations("taskDefList")
  const locale = useLocale()
  const [, setRefresh] = useState(0)
  const totalPages = Math.max(1, Math.ceil(total / pageSize))

  function statusBadge(status: string) {
    if (status === "ONLINE") return <Badge variant="success">{t("statusOnline")}</Badge>
    return <Badge variant="outline" className="text-muted-foreground">{t("statusDraft")}</Badge>
  }

  async function doAction(task: TaskDef, action: string) {
    try {
      if (action === "publish") {
        await authFetch(`${API_BASE}/api/tasks/${task.id}/publish`, { method: "POST" })
      } else if (action === "offline") {
        await authFetch(`${API_BASE}/api/tasks/${task.id}/offline`, { method: "POST" })
      } else if (action === "delete") {
        await authFetch(`${API_BASE}/api/tasks/${task.id}`, { method: "DELETE" })
      }
      setRefresh(n => n + 1)
      onRefresh()
    } catch { /* ignore */ }
  }

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center gap-2">
        <HugeiconsIcon icon={CheckListIcon} className="text-primary" />
        <h2 className="text-sm font-medium">{t("title")}</h2>
        <span className="rounded-md bg-muted px-2 py-0.5 font-mono text-xs text-muted-foreground">
          {total}
        </span>
      </div>

      {tasks.length === 0 ? (
        <div className="flex flex-col items-center justify-center gap-3 py-16 text-center">
          <div className="flex size-12 items-center justify-center rounded-xl bg-muted text-muted-foreground">
            <HugeiconsIcon icon={BoxIcon} className="size-6" />
          </div>
          <p className="text-sm text-muted-foreground">{t("emptyTitle")}</p>
          <p className="max-w-sm text-xs text-muted-foreground">
            {t("emptyHint")}
          </p>
        </div>
      ) : (
        <>
          <div className="font-sans">
            <Table className="table-fixed">
              <TableHeader>
                <TableRow>
                  <TableHead className="w-44">{t("colName")}</TableHead>
                  <TableHead className="w-20">{t("colType")}</TableHead>
                  <TableHead className="w-20">{t("colStatus")}</TableHead>
                  <TableHead className="w-14 text-right">{t("colPriority")}</TableHead>
                  <TableHead className="w-16 text-right">{t("colVersion")}</TableHead>
                  <TableHead className="w-40">{t("colCreatedAt")}</TableHead>
                  <TableHead className="w-40 text-right">{t("colActions")}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {tasks.map((task) => (
                  <TableRow key={task.id}>
                    <TableCell className="w-44 max-w-0 truncate font-medium" title={task.name}>
                      {task.name}
                      {task.description && (
                        <div className="truncate text-xs font-normal text-muted-foreground">{task.description}</div>
                      )}
                    </TableCell>
                    <TableCell className="font-mono text-xs">{task.type}</TableCell>
                    <TableCell>{statusBadge(task.status)}</TableCell>
                    <TableCell className="text-right tabular-nums">{task.priority ?? 5}</TableCell>
                    <TableCell className="text-right tabular-nums">v{task.currentVersionNo}</TableCell>
                    <TableCell className="tabular-nums">{formatDateTime(task.createdAt, locale)}</TableCell>
                    <TableCell className="text-right">
                      <div className="flex justify-end gap-1">
                        <Button variant="ghost" size="sm" className="h-6 px-2 text-xs" onClick={() => onEdit(task)}>{t("btnEdit")}</Button>
                        {task.status === "DRAFT" && (
                          <Button variant="ghost" size="sm" className="h-6 px-2 text-xs" onClick={() => doAction(task, "publish")}>{t("btnPublish")}</Button>
                        )}
                        {task.status === "ONLINE" && (
                          <Button variant="ghost" size="sm" className="h-6 px-2 text-xs text-destructive" onClick={() => doAction(task, "offline")}>{t("btnOffline")}</Button>
                        )}
                        {task.status === "DRAFT" && (
                          <Button variant="ghost" size="sm" className="h-6 px-2 text-xs text-destructive" onClick={() => doAction(task, "delete")}>{t("btnDelete")}</Button>
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
                {t("pageInfo", { total, page: page + 1, totalPages })}
              </span>
              <div className="flex gap-1">
                <Button variant="outline" size="sm" className="h-7 text-xs" disabled={page === 0} onClick={() => onPageChange(page - 1)}>{t("prevPage")}</Button>
                <Button variant="outline" size="sm" className="h-7 text-xs" disabled={page >= totalPages - 1} onClick={() => onPageChange(page + 1)}>{t("nextPage")}</Button>
              </div>
            </div>
          )}
        </>
      )}
    </div>
  )
}
