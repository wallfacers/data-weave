import { SqlWorkbench } from "@/components/sql-workbench"
import { TaskDefList } from "@/components/ops/task-def-list"
import { API_BASE, type TaskDef } from "@/lib/types"

async function getTaskDefs(): Promise<TaskDef[]> {
  try {
    const res = await fetch(`${API_BASE}/api/ops/tasks`, {
      cache: "no-store",
    })
    if (!res.ok) return []
    return res.json()
  } catch {
    return []
  }
}

export default async function TasksPage() {
  const tasks = await getTaskDefs()

  return (
    <div className="flex flex-col gap-8 overflow-auto">
      <div className="shrink-0" style={{ height: "58vh" }}>
        <SqlWorkbench />
      </div>
      <div className="shrink-0 px-4 pb-6">
        <TaskDefList tasks={tasks} />
      </div>
    </div>
  )
}
