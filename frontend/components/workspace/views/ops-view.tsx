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
} from "@hugeicons/core-free-icons"

import type { ViewProps } from "@/lib/workspace/registry"
import { OpsTopStrip } from "./ops/top-strip"
import { PeriodicInstancesPanel } from "./ops/periodic-instances-panel"
import { BackfillPanel } from "./ops/backfill-panel"
import { PeriodicWorkflowsPanel } from "./ops/periodic-workflows-panel"
import { ManualWorkflowsPanel } from "./ops/manual-workflows-panel"

// 运维主体 = 任务流（ops-center-publish-boundary）：周期任务流列表 / 手动任务流列表 / 任务流实例 / 补数据实例。
// 「手动·测试」Tab 已移除：测试实例归开发态，手动触发是实例视图里的动作。
type TabId = "periodicWf" | "manualWf" | "instances" | "backfill"

const TAB_ORDER: { id: TabId; labelKey: string; icon: typeof BoxIcon }[] = [
  { id: "periodicWf", labelKey: "tabPeriodicWorkflows", icon: Calendar03Icon },
  { id: "manualWf", labelKey: "tabManualWorkflows", icon: CursorMagicSelection02Icon },
  { id: "instances", labelKey: "tabWorkflowInstances", icon: RefreshIcon },
  { id: "backfill", labelKey: "tabBackfillInstances", icon: Loading03Icon },
]

export function OpsView({ params }: ViewProps) {
  const t = useTranslations("ops")

  // 初始 Tab / 筛选来自 ui.open 的 params
  const initialTab = useMemo<TabId>(() => {
    const p = (params?.tab as string) ?? "periodicWf"
    return (TAB_ORDER.some((tb) => tb.id === p) ? p : "periodicWf") as TabId
  }, [params?.tab])

  const [activeTab, setActiveTab] = useState<TabId>(initialTab)

  // 预置筛选（来自 dataweave.ui.open）：传给周期实例面板
  const initialFilter = (params?.filter as Record<string, string>) ?? undefined

  return (
    <div className="flex h-full flex-col">
      {/* Header */}
      <div className="flex items-center justify-between border-b px-6 py-4">
        <div className="flex flex-col gap-0.5">
          <h1 className="text-xl font-semibold tracking-tight">{t("title")}</h1>
          <p className="text-sm text-muted-foreground">{t("subtitle")}</p>
        </div>
      </div>

      <OpsTopStrip />

      {/* 主舞台 */}
      <main className="flex min-h-0 flex-1 flex-col">
        <OpsTabBar active={activeTab} onChange={setActiveTab} />
        <div className="flex min-h-0 flex-1">
          {activeTab === "periodicWf" && <PeriodicWorkflowsPanel />}
          {activeTab === "manualWf" && <ManualWorkflowsPanel />}
          {activeTab === "instances" && (
            <PeriodicInstancesPanel initialFilter={initialFilter} />
          )}
          {activeTab === "backfill" && <BackfillPanel />}
        </div>
      </main>
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
    <div className="flex items-center gap-1 border-b px-5 h-11" role="tablist">
      {TAB_ORDER.map((tb) => {
        const isActive = tb.id === active
        return (
          <button
            key={tb.id}
            type="button"
            role="tab"
            aria-selected={isActive}
            onClick={() => onChange(tb.id)}
            className={
              "relative flex items-center gap-1.5 px-3 py-1 text-sm transition-colors " +
              (isActive
                ? "font-medium text-foreground after:absolute after:inset-x-2 after:bottom-0 after:h-0.5 after:rounded-full after:bg-primary"
                : "text-muted-foreground hover:text-foreground")
            }
          >
            <HugeiconsIcon icon={tb.icon} className="size-4" />
            {t(tb.labelKey)}
          </button>
        )
      })}
    </div>
  )
}
