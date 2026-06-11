"use client"

import { HugeiconsIcon } from "@hugeicons/react"
import { ComputerSettingsIcon } from "@hugeicons/core-free-icons"

import { InstanceTable } from "@/components/ops/instance-table"
import { TaskDefList } from "@/components/ops/task-def-list"
import { type TaskDef, type TaskInstance } from "@/lib/types"
import { useApi } from "@/lib/workspace/use-api"
import { ViewStatus } from "./view-status"

export function TaskFlowView({ params }: { params?: Record<string, unknown> }) {
  const instances = useApi<TaskInstance[]>("/api/ops/instances")
  const tasks = useApi<TaskDef[]>("/api/ops/tasks")
  const highlightTaskId = params?.highlightTaskId

  if (!instances.data && (instances.loading || instances.error)) {
    return <ViewStatus loading={instances.loading} />
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
      <TaskDefList tasks={tasks.data ?? []} />
    </div>
  )
}
