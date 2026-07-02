"use client"

/**
 * 运维中心视图：顶条今日大盘 + 主舞台 Tab。
 *
 * 主舞台 Tab：周期任务流列表 / 手动任务流列表 / 任务流实例 / 补数据实例。
 * 经 `dataweave.ui.open({ view: "ops", params: { tab, filter } })` 召唤时，params 用来预置激活 Tab 与筛选。
 */

import { useMemo, useState } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  Loading03Icon,
  Calendar03Icon,
  BoxIcon,
  RefreshIcon,
  CursorMagicSelection02Icon,
  Task01Icon,
} from "@hugeicons/core-free-icons"

import type { ViewProps } from "@/lib/workspace/registry"
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { OpsTopStrip } from "./ops/top-strip"
import { PeriodicInstancesPanel } from "./ops/periodic-instances-panel"
import { WorkflowInstancesPanel } from "./ops/workflow-instances-panel"
import { InstanceDagDialog } from "./ops/instance-dag-dialog"
import { BackfillPanel } from "./ops/backfill-panel"
import { PeriodicWorkflowsPanel } from "./ops/periodic-workflows-panel"
import { ManualWorkflowsPanel } from "./ops/manual-workflows-panel"

// 运维主体 = 任务流（ops-center-publish-boundary）：周期任务流列表 / 手动任务流列表 / 任务流实例 / 任务实例 / 补数据实例。
type TabId = "periodicWf" | "manualWf" | "workflowInstances" | "taskInstances" | "backfill"

const TAB_ORDER: { id: TabId; labelKey: string; icon: typeof BoxIcon }[] = [
  { id: "periodicWf", labelKey: "tabPeriodicWorkflows", icon: Calendar03Icon },
  { id: "manualWf", labelKey: "tabManualWorkflows", icon: CursorMagicSelection02Icon },
  { id: "workflowInstances", labelKey: "tabWorkflowInstances", icon: RefreshIcon },
  { id: "taskInstances", labelKey: "tabTaskInstances", icon: Task01Icon },
  { id: "backfill", labelKey: "tabBackfillInstances", icon: Loading03Icon },
]

export function OpsView({ params, active }: ViewProps) {
  const t = useTranslations("ops")

  // 初始 Tab / 筛选来自 ui.open 的 params
  const initialTab = useMemo<TabId>(() => {
    const p = (params?.tab as string) ?? "periodicWf"
    return (TAB_ORDER.some((tb) => tb.id === p) ? p : "periodicWf") as TabId
  }, [params?.tab])

  const [activeTab, setActiveTab] = useState<TabId>(initialTab)
  const [dagWfInstanceId, setDagWfInstanceId] = useState<string | null>(null)
  const [dagOpen, setDagOpen] = useState(false)

  // 预置筛选（来自 dataweave.ui.open）：传给周期实例面板
  const initialFilter = (params?.filter as Record<string, string>) ?? undefined

  return (
    <div className="flex h-full flex-col">
      <OpsTopStrip active={active} />

      {/* 主舞台 */}
      <div className="flex min-h-0 flex-1">
        <main className="flex min-w-0 flex-1 flex-col">
          <OpsTabBar active={activeTab} onChange={setActiveTab} />
          <div className="flex min-h-0 flex-1">
            {activeTab === "periodicWf" && <PeriodicWorkflowsPanel />}
            {activeTab === "manualWf" && <ManualWorkflowsPanel />}
            {activeTab === "workflowInstances" && (
              <WorkflowInstancesPanel
                active={active}
                onViewDag={(row) => {
                  setDagWfInstanceId(row.id)
                  setDagOpen(true)
                }}
              />
            )}
            {activeTab === "taskInstances" && (
              <PeriodicInstancesPanel initialFilter={initialFilter} active={active} />
            )}
            {activeTab === "backfill" && <BackfillPanel />}
          </div>
        </main>
      </div>

      {/* 实例 DAG 弹窗 */}
      <InstanceDagDialog
        workflowInstanceId={dagWfInstanceId}
        open={dagOpen}
        onOpenChange={setDagOpen}
      />
    </div>
  )
}

function OpsTabBar({
  active,
  onChange,
}: {
  active: TabId
  onChange: (id: TabId) => void
}) {
  const t = useTranslations("ops")
  return (
    <div className="px-5">
      <Tabs value={active} onValueChange={(v) => onChange(v as TabId)}>
        <TabsList size="md">
          {TAB_ORDER.map((tb) => (
            <TabsTrigger key={tb.id} value={tb.id} icon={tb.icon}>
              {t(tb.labelKey)}
            </TabsTrigger>
          ))}
        </TabsList>
      </Tabs>
    </div>
  )
}
