import { HugeiconsIcon } from "@hugeicons/react"
import { ComputerSettingsIcon } from "@hugeicons/core-free-icons"

import { InstanceTable } from "@/components/ops/instance-table"
import { API_BASE, type TaskInstance } from "@/lib/types"

async function getInstances(): Promise<TaskInstance[]> {
  try {
    const res = await fetch(`${API_BASE}/api/ops/instances`, {
      cache: "no-store",
    })
    if (!res.ok) return []
    return res.json()
  } catch {
    return []
  }
}

export default async function OpsPage() {
  const instances = await getInstances()

  return (
    <div className="flex flex-col gap-6 overflow-auto p-4">
      <div className="flex items-center gap-2">
        <HugeiconsIcon icon={ComputerSettingsIcon} className="text-primary" />
        <h1 className="text-sm font-medium">调度运维</h1>
        <span className="rounded-md bg-muted px-2 py-0.5 font-mono text-xs text-muted-foreground">
          {instances.length} 个实例
        </span>
      </div>
      <InstanceTable instances={instances} />
    </div>
  )
}
