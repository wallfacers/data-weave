"use client"

/**
 * 运维中心视图：顶条今日大盘 + 主舞台 Tab + 右栏 Agent 举手台。
 *
 * 布局参照 cockpit-view 的三段式；主舞台 Tab 切换周期实例 / 补数据 / 手动·测试 / 周期任务。
 * 经 `dataweave.ui.open({ view: "ops", params: { tab, filter } })` 召唤时，params 用来预置激活 Tab 与筛选。
 */

import { useEffect, useMemo, useState } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  Loading03Icon,
  SettingDone02Icon,
  BoxIcon,
  RefreshIcon,
  Bug01Icon,
} from "@hugeicons/core-free-icons"

import { useWorkspaceStore } from "@/lib/workspace/store"
import { useOpsAlertsStore } from "@/lib/workspace/ops-alerts-store"
import { DwScroll } from "@/components/ui/dw-scroll"
import type { ViewProps } from "@/lib/workspace/registry"
import { OpsTopStrip } from "./ops/top-strip"
import { PeriodicInstancesPanel } from "./ops/periodic-instances-panel"
import { BackfillPanel } from "./ops/backfill-panel"
import { PeriodicTasksPanel } from "./ops/periodic-tasks-panel"
import { ManualTestsPanel } from "./ops/manual-tests-panel"
import { OpsAlertCard } from "./ops/ops-alert-card"

type TabId = "instances" | "backfill" | "manual" | "tasks"

const TAB_ORDER: { id: TabId; labelKey: string; icon: typeof BoxIcon }[] = [
  { id: "instances", labelKey: "tabPeriodicInstances", icon: RefreshIcon },
  { id: "backfill", labelKey: "tabBackfillInstances", icon: Loading03Icon },
  { id: "manual", labelKey: "tabManualTests", icon: BoxIcon },
  { id: "tasks", labelKey: "tabPeriodicTasks", icon: SettingDone02Icon },
]

/** 开发/测试用 mock alert 注入：`window.__MOCK_OPS_ALERT__` 存在时自动推入 store */
function useMockAlertInjector() {
  useEffect(() => {
    const w = window as typeof window & {
      __MOCK_OPS_ALERT__?: Record<string, unknown>
    }
    if (w.__MOCK_OPS_ALERT__) {
      useOpsAlertsStore.getState().push({
        id: String(w.__MOCK_OPS_ALERT__.id ?? "mock-alert"),
        kind: (w.__MOCK_OPS_ALERT__.kind as "INSTANCE_FAILED") ?? "INSTANCE_FAILED",
        severity: (w.__MOCK_OPS_ALERT__.severity as "error") ?? "error",
        title: String(w.__MOCK_OPS_ALERT__.title ?? "Mock alert"),
        detail: w.__MOCK_OPS_ALERT__.detail as string | undefined,
        instanceIds: Array.isArray(w.__MOCK_OPS_ALERT__.instanceIds)
          ? (w.__MOCK_OPS_ALERT__.instanceIds as string[])
          : [],
        suggestedAction: w.__MOCK_OPS_ALERT__.suggestedAction as
          | { op: "rerun"; params: Record<string, unknown> }
          | undefined,
        receivedAt: Date.now(),
      })
    }
  }, [])
}

export function OpsView({ params }: ViewProps) {
  const t = useTranslations("ops")
  const open = useWorkspaceStore((s) => s.open)
  useMockAlertInjector()

  // 初始 Tab / 筛选来自 ui.open 的 params
  const initialTab = useMemo<TabId>(() => {
    const p = (params?.tab as string) ?? "instances"
    return (TAB_ORDER.some((tb) => tb.id === p) ? p : "instances") as TabId
  }, [params?.tab])

  const [activeTab, setActiveTab] = useState<TabId>(initialTab)
  const alerts = useOpsAlertsStore((s) => s.alerts)
  const activeAlerts = alerts.filter((a) => !a.resolved)

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

      {/* 主舞台 + 右栏举手台 */}
      <div className="flex min-h-0 flex-1">
        <main className="flex min-w-0 flex-1 flex-col border-r">
          <OpsTabBar active={activeTab} onChange={setActiveTab} />
          <div className="flex min-h-0 flex-1">
            {activeTab === "instances" && (
              <PeriodicInstancesPanel initialFilter={initialFilter} />
            )}
            {activeTab === "backfill" && <BackfillPanel />}
            {activeTab === "manual" && <ManualTestsPanel />}
            {activeTab === "tasks" && <PeriodicTasksPanel />}
          </div>
        </main>

        <aside className="flex w-[360px] shrink-0 flex-col">
          <div className="flex items-center gap-2 border-b px-5 py-3">
            <HugeiconsIcon icon={Bug01Icon} className="size-4 text-primary" />
            <h2 className="text-sm font-semibold tracking-tight">{t("railTitle")}</h2>
            {activeAlerts.length > 0 && (
              <span className="ml-auto rounded-full bg-destructive/10 px-2 py-0.5 text-xs font-medium text-destructive tabular-nums">
                {activeAlerts.length}
              </span>
            )}
          </div>
          {activeAlerts.length === 0 ? (
            <div className="flex flex-1 items-center justify-center p-8 text-center">
              <p className="text-sm text-muted-foreground">{t("railEmpty")}</p>
            </div>
          ) : (
            <DwScroll className="flex-1" innerClassName="flex flex-col gap-3 p-4">
              {activeAlerts.map((a) => (
                <OpsAlertCard key={a.id} alert={a} />
              ))}
            </DwScroll>
          )}
        </aside>
      </div>
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
    <div className="flex items-center gap-1 border-b px-5" role="tablist">
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
              "relative flex items-center gap-1.5 px-3 py-2.5 text-sm transition-colors " +
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
