"use client"

import { useEffect, useState } from "react"
import { HugeiconsIcon } from "@hugeicons/react"
import { Flowchart01Icon } from "@hugeicons/core-free-icons"

import { Badge } from "@/components/ui/badge"
import { useEventSource } from "@/lib/workspace/use-event-source"
import { DwScroll } from "@/components/ui/dw-scroll"
import { API_BASE, authFetch, type ApiResponse } from "@/lib/types"

interface WorkflowInstanceDetailProps {
  params?: Record<string, unknown>
}

interface WorkflowInstance {
  id: string
  workflowId: number
  bizDate: string
  state: string
  priority: number
  runMode: string
  startedAt: string
  finishedAt: string | null
  tasks: TaskNode[]
}

interface TaskNode {
  id: string
  taskDefId: number
  taskDefName: string
  state: string
  workerNodeCode: string | null
  attempt: number
}

function stateColor(state: string): string {
  switch (state) {
    case "SUCCESS":
      return "bg-success/15 text-success border-success/30"
    case "FAILED":
      return "bg-destructive/15 text-destructive border-destructive/30"
    case "RUNNING":
      return "bg-info/15 text-info border-info/30"
    case "WAITING":
      return "bg-warning/15 text-warning border-warning/30"
    case "DISPATCHED":
      return "bg-info/15 text-info border-info/30"
    case "NOT_RUN":
      return "bg-muted text-muted-foreground border-border"
    default:
      return "bg-muted text-muted-foreground border-border"
  }
}

export function WorkflowInstanceDetail({ params }: WorkflowInstanceDetailProps) {
  const instanceId = params?.instanceId as string | undefined
  const [instance, setInstance] = useState<WorkflowInstance | null>(null)
  const [loading, setLoading] = useState(true)

  // 订阅状态事件流
  const { events, connected } = useEventSource(
    instanceId ? `${API_BASE}/api/ops/workflow-instances/${instanceId}/events/stream` : "",
  )

  // 初始加载
  useEffect(() => {
    if (!instanceId) return
    setLoading(true)
    authFetch(`${API_BASE}/api/ops/workflow-instances/${instanceId}`)
      .then((res) => res.json() as Promise<ApiResponse<WorkflowInstance>>)
      .then((json) => {
        if (json.code === 0) {
          setInstance(json.data)
        }
      })
      .catch(() => {})
      .finally(() => setLoading(false))
  }, [instanceId])

  // 监听状态事件，更新节点状态
  useEffect(() => {
    if (events.length === 0 || !instance) return

    const statusEvents = events.filter((e) => e.type === "status")
    if (statusEvents.length === 0) return

    // 解析最新的状态事件
    const latestEvent = statusEvents[statusEvents.length - 1]
    try {
      const update = JSON.parse(latestEvent.data) as {
        taskId?: string
        taskState?: string
        workflowState?: string
      }

      setInstance((prev) => {
        if (!prev) return prev
        const updated = { ...prev, tasks: [...prev.tasks] }

        if (update.workflowState) {
          updated.state = update.workflowState
        }

        if (update.taskId && update.taskState) {
          const taskIdx = updated.tasks.findIndex((t) => t.id === update.taskId)
          if (taskIdx >= 0) {
            updated.tasks[taskIdx] = {
              ...updated.tasks[taskIdx],
              state: update.taskState,
            }
          }
        }

        return updated
      })
    } catch {
      // 忽略解析错误
    }
  }, [events, instance])

  if (!instanceId) {
    return (
      <div className="flex flex-1 items-center justify-center text-sm text-muted-foreground">
        未指定工作流实例 ID
      </div>
    )
  }

  if (loading) {
    return (
      <div className="flex flex-1 items-center justify-center text-sm text-muted-foreground">
        加载中…
      </div>
    )
  }

  if (!instance) {
    return (
      <div className="flex flex-1 items-center justify-center text-sm text-muted-foreground">
        工作流实例不存在
      </div>
    )
  }

  return (
    <DwScroll className="flex-1" innerClassName="flex flex-col gap-4 p-4">
      <div className="flex items-center gap-2">
        <HugeiconsIcon icon={Flowchart01Icon} className="text-primary" />
        <h1 className="text-sm font-medium">工作流实例</h1>
        <span className="font-mono text-xs text-muted-foreground">#{instance.id}</span>
        <Badge
          variant={
            instance.state === "SUCCESS"
              ? "success"
              : instance.state === "FAILED"
                ? "destructive"
                : "info"
          }
          className="ml-auto"
        >
          {instance.state}
        </Badge>
        <Badge variant={connected ? "success" : "outline"}>
          {connected ? "实时" : "离线"}
        </Badge>
      </div>

      <div className="rounded-lg border bg-card p-4">
        <div className="grid grid-cols-2 gap-4 text-sm">
          <div>
            <span className="text-muted-foreground">工作流 #</span>
            <span className="ml-2 font-mono">{instance.workflowId}</span>
          </div>
          <div>
            <span className="text-muted-foreground">业务日期</span>
            <span className="ml-2 font-mono">{instance.bizDate}</span>
          </div>
          <div>
            <span className="text-muted-foreground">优先级</span>
            <span className="ml-2 font-mono">{instance.priority}</span>
          </div>
          <div>
            <span className="text-muted-foreground">运行模式</span>
            <span className="ml-2 font-mono">{instance.runMode}</span>
          </div>
        </div>
      </div>

      <div>
        <h2 className="mb-2 text-sm font-medium text-muted-foreground">DAG 节点</h2>
        <div className="space-y-2">
          {instance.tasks.map((task) => (
            <div
              key={task.id}
              className={`flex items-center gap-3 rounded-lg border p-3 transition-colors ${stateColor(task.state)}`}
            >
              <div className="flex-1">
                <div className="text-sm font-medium">{task.taskDefName}</div>
                <div className="mt-1 text-xs text-muted-foreground">
                  任务 #{task.taskDefId} · 尝试 {task.attempt}
                  {task.workerNodeCode && ` · 节点 ${task.workerNodeCode}`}
                </div>
              </div>
              <Badge
                variant={
                  task.state === "SUCCESS"
                    ? "success"
                    : task.state === "FAILED"
                      ? "destructive"
                      : task.state === "RUNNING" || task.state === "DISPATCHED"
                        ? "info"
                        : task.state === "WAITING"
                          ? "warning"
                          : "outline"
                }
              >
                {task.state}
              </Badge>
            </div>
          ))}
        </div>
      </div>
    </DwScroll>
  )
}
