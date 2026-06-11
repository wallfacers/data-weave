"use client"

import { useCallback, useEffect, useState } from "react"
import { HugeiconsIcon } from "@hugeicons/react"
import { ComputerSettingsIcon } from "@hugeicons/core-free-icons"

import { InstanceTable } from "@/components/ops/instance-table"
import { TaskDefList } from "@/components/ops/task-def-list"
import { TaskEditDrawer } from "@/components/ops/task-edit-drawer"
import { TaskSearchBar, type TaskSearchParams } from "@/components/ops/task-search-bar"
import { type TaskDef, type TaskInstance, API_BASE } from "@/lib/types"
import { useApi } from "@/lib/workspace/use-api"
import { ViewStatus } from "./view-status"

interface PageResult {
  content: TaskDef[]
  totalElements: number
  totalPages: number
  page: number
  size: number
}

export function TaskFlowView({ params }: { params?: Record<string, unknown> }) {
  const instances = useApi<TaskInstance[]>("/api/ops/instances")
  const highlightTaskId = params?.highlightTaskId

  // Task search state
  const [searchParams, setSearchParams] = useState<TaskSearchParams>({ keyword: "", type: "", status: "" })
  const [page, setPage] = useState(0)
  const [taskData, setTaskData] = useState<PageResult | null>(null)
  const [taskLoading, setTaskLoading] = useState(true)

  // Drawer state
  const [drawerOpen, setDrawerOpen] = useState(false)
  const [editTask, setEditTask] = useState<TaskDef | null>(null)

  const fetchTasks = useCallback(() => {
    setTaskLoading(true)
    const sp = new URLSearchParams()
    if (searchParams.keyword) sp.set("keyword", searchParams.keyword)
    if (searchParams.type) sp.set("type", searchParams.type)
    if (searchParams.status) sp.set("status", searchParams.status)
    sp.set("page", String(page))
    sp.set("size", "20")
    fetch(`${API_BASE}/api/tasks?${sp}`, { cache: "no-store" })
      .then((res) => res.json())
      .then((data) => setTaskData(data))
      .catch(() => setTaskData(null))
      .finally(() => setTaskLoading(false))
  }, [searchParams, page])

  useEffect(() => { fetchTasks() }, [fetchTasks])

  // Reset page on search change
  useEffect(() => { setPage(0) }, [searchParams])

  if (!instances.data && (instances.loading || instances.error)) {
    return <ViewStatus loading={instances.loading} />
  }

  function handleNewTask() {
    setEditTask(null)
    setDrawerOpen(true)
  }

  function handleEdit(task: TaskDef) {
    setEditTask(task)
    setDrawerOpen(true)
  }

  return (
    <div className="flex flex-1 flex-col gap-6 overflow-auto p-4">
      <div className="flex items-center gap-2">
        <HugeiconsIcon icon={ComputerSettingsIcon} className="text-primary" />
        <h1 className="text-sm font-medium">任务流</h1>
        <span className="rounded-md bg-muted px-2 py-0.5 font-mono text-xs text-muted-foreground">
          {instances.data?.length ?? 0} 个实例
        </span>
        {highlightTaskId != null && (
          <span className="rounded-md bg-primary/10 px-2 py-0.5 font-mono text-xs text-primary">
            聚焦任务 #{String(highlightTaskId)}
          </span>
        )}
      </div>

      <InstanceTable instances={instances.data ?? []} />

      <TaskSearchBar
        params={searchParams}
        onChange={setSearchParams}
        onNewTask={handleNewTask}
      />

      <TaskDefList
        tasks={taskData?.content ?? []}
        total={taskData?.totalElements ?? 0}
        page={page}
        pageSize={taskData?.size ?? 20}
        onPageChange={setPage}
        onEdit={handleEdit}
        onRefresh={fetchTasks}
      />

      {taskLoading && (
        <div className="py-4 text-center text-xs text-muted-foreground">加载中…</div>
      )}

      <TaskEditDrawer
        open={drawerOpen}
        onOpenChange={setDrawerOpen}
        task={editTask}
        onSaved={fetchTasks}
      />
    </div>
  )
}
