"use client"

import { useState } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import { ServerStackIcon } from "@hugeicons/core-free-icons"

import { FleetCard } from "@/components/cockpit/fleet-card"
import { type WorkerNode } from "@/lib/types"
import { fetchApi, useLiveData } from "@/lib/workspace/use-api"
import type { ViewProps } from "@/lib/workspace/registry"
import { DwScroll } from "@/components/ui/dw-scroll"
import { ViewRefreshControl } from "./view-refresh-control"

export function FleetView({ active }: ViewProps) {
  const t = useTranslations("fleet")
  const tc = useTranslations("common")
  const [autoEnabled, setAutoEnabled] = useState(true)

  const {
    data: nodes,
    loading,
    refreshing,
    stale,
    lastUpdatedAt,
    refresh,
  } = useLiveData<WorkerNode[]>(() => fetchApi<WorkerNode[]>("/api/fleet"), {
    active,
    enabled: autoEnabled,
  })

  return (
    <div className="flex min-h-0 min-w-0 flex-1 flex-col p-5">
      <div className="shrink-0 flex items-center justify-between pb-3">
        <p className="text-xs text-muted-foreground">{t("subtitle")}</p>
        <ViewRefreshControl
          lastUpdatedAt={lastUpdatedAt}
          refreshing={refreshing}
          stale={stale}
          autoEnabled={autoEnabled}
          onToggleAuto={setAutoEnabled}
          onRefresh={refresh}
        />
      </div>
      <DwScroll className="flex-1" innerClassName="flex flex-col gap-4 min-h-full">
        {!nodes ? (
          <div className="flex flex-1 items-center justify-center p-10 text-center">
            <p className="text-muted-foreground">{tc("loading")}</p>
          </div>
        ) : nodes.length === 0 ? (
          <div className="flex flex-1 flex-col items-center justify-center gap-2 text-muted-foreground">
            <HugeiconsIcon icon={ServerStackIcon} size={32} />
            <p className="text-sm">{t("empty")}</p>
          </div>
        ) : (
          <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
            {nodes.map((node) => (
              <FleetCard key={node.id} node={node} />
            ))}
          </div>
        )}
      </DwScroll>
    </div>
  )
}
