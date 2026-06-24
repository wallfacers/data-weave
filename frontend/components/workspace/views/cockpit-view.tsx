"use client"

import { useTranslations, useLocale } from "next-intl"
import { HugeiconsIcon, type IconSvgElement } from "@hugeicons/react"
import {
  Activity02Icon,
  Loading03Icon,
  HourglassIcon,
  Alert01Icon,
  ArrowDataTransferHorizontalIcon,
  TimeManagementIcon,
  ServerStack01Icon,
} from "@hugeicons/core-free-icons"

import { Button } from "@/components/ui/button"
import { FindingsRail } from "@/components/cockpit/findings-rail"
import { type DashboardSummary } from "@/lib/types"
import { useApi } from "@/lib/workspace/use-api"
import { useWorkspaceStore } from "@/lib/workspace/store"
import { ViewStatus } from "./view-status"
import { LineageGraph } from "./lineage-graph"

/** 后端 MetricsSnapshot 子集：顶条只需队列深度。 */
interface MetricsSnapshot {
  queueDepth: number
}

/** 今日同步行数聚合（运行态）；syncedRows=null 表示无采集数据。 */
interface SyncSummary {
  syncedRows: number | null
}

/** 行数按 locale 紧凑格式化：zh→「1.9亿」、en→「186M」（Intl 内建，单位随 locale，无硬编码）。 */
function formatRows(locale: string, n: number): string {
  return new Intl.NumberFormat(locale, {
    notation: "compact",
    maximumFractionDigits: 2,
  }).format(n)
}

/** 顶条「最迟看板 ETA」聚合：运行中实例里最迟预计完成；null=冷启动无样本。 */
interface EtaSummary {
  latestEta: string
  remainingSeconds: number
  runningCount: number
  predictedCount: number
}

/** 剩余秒数格式化为 ETA 文案。 */
function formatEta(t: (k: string) => string, sec: number): string {
  if (sec <= 0) return t("etaImminent")
  const min = Math.round(sec / 60)
  if (min < 60) return `${t("etaAboutPrefix")}${min}${t("etaMinUnit")}`
  const h = Math.floor(min / 60)
  const m = min % 60
  return m > 0
    ? `${t("etaAboutPrefix")}${h}${t("etaHourUnit")}${m}${t("etaMinUnit")}`
    : `${t("etaAboutPrefix")}${h}${t("etaHourUnit")}`
}

type Tone = "default" | "success" | "destructive" | "warning" | "info"

const TONE_CLASSES: Record<Tone, string> = {
  default: "bg-muted text-muted-foreground",
  success: "bg-success/10 text-success",
  destructive: "bg-destructive/10 text-destructive",
  warning: "bg-warning/10 text-warning",
  info: "bg-info/10 text-info",
}

/** 顶条聚合 pill：一眼健康，面向非开发人员。 */
function TopStat({
  label,
  value,
  icon,
  tone,
}: {
  label: string
  value: string
  icon: IconSvgElement
  tone?: Tone
}) {
  return (
    <div className="flex items-center gap-2.5 rounded-xl border bg-card px-3 py-2.5">
      <div
        className={`flex size-8 shrink-0 items-center justify-center rounded-lg ${TONE_CLASSES[tone ?? "default"]}`}
      >
        <HugeiconsIcon icon={icon} className="size-4" />
      </div>
      <div className="flex min-w-0 flex-col">
        <span className="text-lg font-semibold tracking-tight font-sans tabular-nums whitespace-nowrap">
          {value}
        </span>
        <span className="truncate text-[11px] text-muted-foreground">{label}</span>
      </div>
    </div>
  )
}

export function CockpitView() {
  const t = useTranslations("lineageCockpit")
  const tc = useTranslations("cockpit")
  const locale = useLocale()
  const open = useWorkspaceStore((s) => s.open)
  const { data: summary, loading } = useApi<DashboardSummary>("/api/ops/summary")
  const { data: metrics } = useApi<MetricsSnapshot>("/api/ops/metrics")
  const { data: syncSummary } = useApi<SyncSummary>("/api/lineage/sync-summary")
  const { data: etaSummary } = useApi<EtaSummary>("/api/ops/eta-summary")

  if (!summary) return <ViewStatus loading={loading} />

  const healthPct = summary.total > 0 ? Math.round((summary.success / summary.total) * 100) : 100
  const healthTone: Tone =
    healthPct >= 90 ? "success" : healthPct >= 70 ? "warning" : "destructive"
  const queued = metrics?.queueDepth ?? 0
  const syncedRows = syncSummary?.syncedRows ?? null
  const syncValue = syncedRows != null ? formatRows(locale, syncedRows) : t("estimating")
  const etaValue = etaSummary
    ? formatEta(t, etaSummary.remainingSeconds)
    : t("estimating")

  return (
    <div className="flex h-full flex-col">
      {/* Header */}
      <div className="flex items-center justify-between border-b px-6 py-4">
        <div className="flex flex-col gap-0.5">
          <h1 className="text-xl font-semibold tracking-tight">{t("title")}</h1>
          <p className="text-sm text-muted-foreground">{t("subtitle")}</p>
        </div>
        <Button variant="outline" size="sm" onClick={() => open("fleet")}>
          <HugeiconsIcon icon={ServerStack01Icon} data-icon="inline-start" />
          {tc("clusterMachines")}
        </Button>
      </div>

      {/* 顶条聚合：一眼健康 */}
      <div className="grid gap-3 border-b px-6 py-4 grid-cols-2 sm:grid-cols-3 2xl:grid-cols-6">
        <TopStat label={t("topHealth")} value={`${healthPct}%`} icon={Activity02Icon} tone={healthTone} />
        <TopStat label={t("topRunning")} value={String(summary.running)} icon={Loading03Icon} tone="info" />
        <TopStat label={t("topQueued")} value={String(queued)} icon={HourglassIcon} tone={queued > 0 ? "warning" : "default"} />
        <TopStat label={t("topError")} value={String(summary.failed)} icon={Alert01Icon} tone={summary.failed > 0 ? "destructive" : "default"} />
        <TopStat label={t("topSyncToday")} value={syncValue} icon={ArrowDataTransferHorizontalIcon} tone={syncedRows != null ? "info" : "default"} />
        <TopStat label={t("topLatestEta")} value={etaValue} icon={TimeManagementIcon} tone={etaSummary ? "info" : "default"} />
      </div>

      {/* 主体：主舞台 + 右栏举手台 */}
      <div className="flex min-h-0 flex-1">
        {/* 主舞台：跨系统活血缘图（Phase 0 占位） */}
        <main className="flex min-w-0 flex-1 flex-col border-r">
          <div className="flex items-center gap-2 border-b px-5 py-3">
            <HugeiconsIcon icon={Activity02Icon} className="size-4 text-primary" />
            <h2 className="text-sm font-semibold tracking-tight">{t("stageTitle")}</h2>
          </div>
          <LineageGraph />
        </main>

        {/* 右栏：Agent 举手台（通用 Finding[]，由 agent.finding 实时刷新） */}
        <FindingsRail />
      </div>
    </div>
  )
}
