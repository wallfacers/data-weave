"use client"

import { useTranslations } from "next-intl"
import { HugeiconsIcon, type IconSvgElement } from "@hugeicons/react"
import {
  Activity02Icon,
  Loading03Icon,
  HourglassIcon,
  Alert01Icon,
  ArrowDataTransferHorizontalIcon,
  TimeManagementIcon,
  ServerStack01Icon,
  Bug01Icon,
} from "@hugeicons/core-free-icons"

import { Button } from "@/components/ui/button"
import { DiagnosisCard } from "@/components/cockpit/diagnosis-card"
import { type DashboardSummary, type TaskDiagnosis } from "@/lib/types"
import { useApi } from "@/lib/workspace/use-api"
import { useWorkspaceStore } from "@/lib/workspace/store"
import { ViewStatus } from "./view-status"
import { LineageGraph } from "./lineage-graph"
import { DwScroll } from "@/components/ui/dw-scroll"

/** 后端 MetricsSnapshot 子集：顶条只需队列深度。 */
interface MetricsSnapshot {
  queueDepth: number
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
  const open = useWorkspaceStore((s) => s.open)
  const { data: summary, loading } = useApi<DashboardSummary>("/api/ops/summary")
  const { data: metrics } = useApi<MetricsSnapshot>("/api/ops/metrics")
  const { data: diagnoses } = useApi<TaskDiagnosis[]>("/api/diagnosis")

  if (!summary) return <ViewStatus loading={loading} />

  const healthPct = summary.total > 0 ? Math.round((summary.success / summary.total) * 100) : 100
  const healthTone: Tone =
    healthPct >= 90 ? "success" : healthPct >= 70 ? "warning" : "destructive"
  const queued = metrics?.queueDepth ?? 0
  const openDiagnoses = (diagnoses ?? []).filter((d) => d.status === "OPEN")

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
        <TopStat label={t("topSyncToday")} value={t("estimating")} icon={ArrowDataTransferHorizontalIcon} />
        <TopStat label={t("topLatestEta")} value={t("estimating")} icon={TimeManagementIcon} />
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

        {/* 右栏：Agent 举手台 */}
        <aside className="flex w-[360px] shrink-0 flex-col">
          <div className="flex items-center gap-2 border-b px-5 py-3">
            <HugeiconsIcon icon={Bug01Icon} className="size-4 text-primary" />
            <h2 className="text-sm font-semibold tracking-tight">{t("railTitle")}</h2>
            {openDiagnoses.length > 0 && (
              <span className="ml-auto rounded-full bg-destructive/10 px-2 py-0.5 text-xs font-medium text-destructive tabular-nums">
                {openDiagnoses.length}
              </span>
            )}
          </div>
          {openDiagnoses.length === 0 ? (
            <div className="flex flex-1 items-center justify-center p-8 text-center">
              <p className="text-sm text-muted-foreground">{t("railEmpty")}</p>
            </div>
          ) : (
            <DwScroll className="flex-1" innerClassName="flex flex-col gap-3 p-4">
              {openDiagnoses.map((d) => (
                <DiagnosisCard key={d.id} diagnosis={d} />
              ))}
            </DwScroll>
          )}
        </aside>
      </div>
    </div>
  )
}
