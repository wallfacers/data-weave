"use client"

import { useTranslations } from "next-intl"
import { HugeiconsIcon, type IconSvgElement } from "@hugeicons/react"
import {
  Activity02Icon,
  ArrowRight01Icon,
  TimeManagementIcon,
  HourglassIcon,
  CpuIcon,
  CheckmarkCircle01Icon,
  Alert01Icon,
  SignalIcon,
  SatelliteIcon,
} from "@hugeicons/core-free-icons"

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { useApi } from "@/lib/workspace/use-api"
import { ViewStatus } from "./view-status"
import { DwScroll } from "@/components/ui/dw-scroll"

// ─── 后端 MetricsSnapshot 对应类型 ──────────────────────────

interface MetricsSnapshot {
  // 第 1 层
  dispatchLatencyMean: number
  dispatchLatencyCount: number
  deliveryLatencyMean: number
  deliveryLatencyCount: number
  queueDepth: number
  oldestAgeSeconds: number
  totalClaimRounds: number
  emptyClaimRounds: number
  wakeEvents: number
  wakePolls: number
  totalDispatches: number
  roundDurationMean: number
  // 第 2 层
  slotUtilization: number
  slotFragmentation: number
  taskDurationMean: number
  taskCompletedCount: number
  leaseReclaims: number
  // 第 3 层
  logStreamBacklog: number
  sseConnections: number
}

// ─── 辅助 ──────────────────────────────────────────────────

function fmtMs(ms: number): string {
  if (ms < 1) return "<1 ms"
  if (ms < 1000) return `${Math.round(ms)} ms`
  return `${(ms / 1000).toFixed(1)} s`
}

function fmtSeconds(s: number): string {
  if (s < 60) return `${s} s`
  if (s < 3600) return `${Math.floor(s / 60)} min ${s % 60} s`
  return `${Math.floor(s / 3600)} h ${Math.floor((s % 3600) / 60)} min`
}

function ratioPct(a: number, b: number): string {
  if (b === 0) return "—"
  return `${((a / b) * 100).toFixed(1)}%`
}

function MetricCard({
  label,
  value,
  subtitle,
  icon,
  tone,
}: {
  label: string
  value: string
  subtitle?: string
  icon: IconSvgElement
  tone?: "default" | "success" | "destructive" | "warning" | "info"
}) {
  const toneClasses = {
    default: "bg-muted text-muted-foreground",
    success: "bg-success/10 text-success",
    destructive: "bg-destructive/10 text-destructive",
    warning: "bg-warning/10 text-warning",
    info: "bg-info/10 text-info",
  }

  return (
    <Card>
      <CardContent className="flex items-center gap-4 pt-5">
        <div
          className={`flex size-11 shrink-0 items-center justify-center rounded-xl ${toneClasses[tone ?? "default"]}`}
        >
          <HugeiconsIcon icon={icon} className="size-5" />
        </div>
        <div className="flex flex-col min-w-0">
          <span className="text-2xl font-semibold tracking-tight font-sans tabular-nums truncate">
            {value}
          </span>
          <span className="text-xs text-muted-foreground">{label}</span>
          {subtitle && (
            <span className="text-[10px] text-muted-foreground/60 truncate">{subtitle}</span>
          )}
        </div>
      </CardContent>
    </Card>
  )
}

// ─── 主视图 ─────────────────────────────────────────────────

export function MetricsView() {
  const t = useTranslations("metrics")
  const { data: s, loading } = useApi<MetricsSnapshot>("/api/ops/metrics")

  if (!s) return <ViewStatus loading={loading} />

  const wakeTotal = s.wakeEvents + s.wakePolls
  const emptyRate = s.totalClaimRounds > 0 ? s.emptyClaimRounds / s.totalClaimRounds : 0

  return (
    <DwScroll className="flex-1" innerClassName="flex flex-col gap-8 p-6 md:p-10">
      {/* ─── 调度性能 ──────────────────────────── */}
      <section className="flex flex-col gap-3">
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <MetricCard
            label={t("dispatchLatencyMean")}
            value={fmtMs(s.dispatchLatencyMean)}
            subtitle={t("countTimes", { count: s.dispatchLatencyCount })}
            icon={CheckmarkCircle01Icon}
            tone={s.dispatchLatencyMean > 1000 ? "warning" : "success"}
          />
          <MetricCard
            label={t("deliveryLatencyMean")}
            value={fmtMs(s.deliveryLatencyMean)}
            subtitle={t("countTimes", { count: s.deliveryLatencyCount })}
            icon={SignalIcon}
            tone={s.deliveryLatencyMean > 2000 ? "warning" : "success"}
          />
          <MetricCard
            label={t("queueDepth")}
            value={String(s.queueDepth)}
            icon={ArrowRight01Icon}
            tone={s.queueDepth > 50 ? "warning" : "default"}
          />
          <MetricCard
            label={t("oldestAge")}
            value={fmtSeconds(s.oldestAgeSeconds)}
            icon={HourglassIcon}
            tone={s.oldestAgeSeconds > 300 ? "destructive" : s.oldestAgeSeconds > 120 ? "warning" : "default"}
          />
        </div>
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <MetricCard
            label={t("totalDispatches")}
            value={String(s.totalDispatches)}
            icon={SatelliteIcon}
          />
          <MetricCard
            label={t("roundDuration")}
            value={fmtMs(s.roundDurationMean)}
            subtitle={t("countRounds", { count: s.totalClaimRounds })}
            icon={CpuIcon}
          />
          <MetricCard
            label={t("emptyClaimRate")}
            value={ratioPct(s.emptyClaimRounds, s.totalClaimRounds)}
            subtitle={`${s.emptyClaimRounds} / ${s.totalClaimRounds}`}
            icon={Alert01Icon}
            tone={emptyRate > 0.5 ? "warning" : "default"}
          />
          <MetricCard
            label={t("eventVsPoll")}
            value={`${s.wakeEvents} : ${s.wakePolls}`}
            subtitle={t("eventRatio", { ratio: ratioPct(s.wakeEvents, wakeTotal) })}
            icon={Activity02Icon}
            tone={s.wakePolls > s.wakeEvents ? "warning" : "info"}
          />
        </div>
      </section>

      {/* ─── 资源与执行 ───────────────────────── */}
      <section className="flex flex-col gap-3">
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <MetricCard
            label={t("slotUtilization")}
            value={`${(s.slotUtilization * 100).toFixed(1)}%`}
            icon={SatelliteIcon}
            tone={s.slotUtilization > 0.8 ? "warning" : s.slotUtilization > 0.5 ? "info" : "default"}
          />
          <MetricCard
            label={t("slotFragmentation")}
            value={`${(s.slotFragmentation * 100).toFixed(1)}%`}
            icon={Alert01Icon}
            tone={s.slotFragmentation > 0.5 ? "destructive" : "default"}
          />
          <MetricCard
            label={t("taskDuration")}
            value={fmtMs(s.taskDurationMean)}
            subtitle={t("countCompleted", { count: s.taskCompletedCount })}
            icon={TimeManagementIcon}
          />
          <MetricCard
            label={t("leaseReclaims")}
            value={String(s.leaseReclaims)}
            icon={CheckmarkCircle01Icon}
            tone={s.leaseReclaims > 0 ? "warning" : "success"}
          />
        </div>
      </section>

      {/* ─── 管道健康 ──────────────────────────── */}
      <section className="flex flex-col gap-3">
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          <MetricCard
            label={t("logStreamBacklog")}
            value={String(s.logStreamBacklog)}
            subtitle={t("logStreamBacklogUnit")}
            icon={ArrowRight01Icon}
          />
          <MetricCard
            label={t("sseConnections")}
            value={String(s.sseConnections)}
            icon={SignalIcon}
          />
          <Card>
            <CardContent className="pt-5 flex flex-col items-center justify-center gap-1 min-h-[88px]">
              <span className="text-xs text-muted-foreground">{t("percentileLatencyTitle")}</span>
              <span className="text-[10px] text-muted-foreground/60 text-center">
                {t("percentileLatencyHintLine1")}
                <br />
                {t("percentileLatencyHintLine2")}
              </span>
            </CardContent>
          </Card>
        </div>
      </section>
    </DwScroll>
  )
}
