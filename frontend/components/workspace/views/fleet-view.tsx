"use client"

import { HugeiconsIcon } from "@hugeicons/react"
import { ServerStack01Icon } from "@hugeicons/core-free-icons"

import { FleetCard } from "@/components/cockpit/fleet-card"
import { type WorkerNode } from "@/lib/types"
import { useApi } from "@/lib/workspace/use-api"
import { ViewStatus } from "./view-status"
import { DwScroll } from "@/components/ui/dw-scroll"

export function FleetView() {
  const { data: nodes, loading } = useApi<WorkerNode[]>("/api/fleet")

  if (!nodes) return <ViewStatus loading={loading} />

  return (
    <DwScroll className="flex-1" innerClassName="flex flex-col gap-8 p-6 md:p-10">
      <div className="flex flex-col gap-1">
        <div className="flex items-center gap-2">
          <HugeiconsIcon icon={ServerStack01Icon} className="size-5 text-primary" />
          <h1 className="text-2xl font-semibold tracking-tight">集群机器</h1>
        </div>
        <p className="text-sm text-muted-foreground">Worker 节点状态与资源使用</p>
      </div>

      {nodes.length === 0 ? (
        <div className="flex flex-1 items-center justify-center p-10 text-center">
          <p className="text-muted-foreground">暂无注册的 Worker 节点</p>
        </div>
      ) : (
        <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
          {nodes.map((node) => (
            <FleetCard key={node.id} node={node} />
          ))}
        </div>
      )}
    </DwScroll>
  )
}
