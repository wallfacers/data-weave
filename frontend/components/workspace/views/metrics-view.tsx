"use client"

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
  const { data: s, loading } = useApi<MetricsSnapshot>("/api/ops/metrics")

  if (!s) return <ViewStatus loading={loading} />

  const wakeTotal = s.wakeEvents + s.wakePolls
  const emptyRate = s.totalClaimRounds > 0 ? s.emptyClaimRounds / s.totalClaimRounds : 0

  return (
    <DwScroll className="flex-1" innerClassName="flex flex-col gap-8 p-6 md:p-10">
      {/* Header */}
      <div className="flex flex-col gap-1">
        <div className="flex items-center gap-2">
          <HugeiconsIcon icon={Activity02Icon} className="size-5 text-primary" />
          <h1 className="text-2xl font-semibold tracking-tight">系统指标</h1>
        </div>
        <p className="text-sm text-muted-foreground">
          调度内核四层可观测指标：调度性能、资源执行、管道健康、业务 SLA
        </p>
      </div>

      {/* ─── 第 1 层：调度性能 ──────────────────────────── */}
      <section className="flex flex-col gap-3">
        <div className="flex items-center gap-2">
          <HugeiconsIcon icon={TimeManagementIcon} className="size-4 text-muted-foreground" />
          <h2 className="text-sm font-semibold tracking-tight text-muted-foreground uppercase">
            调度性能
          </h2>
        </div>
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <MetricCard
            label="调度延迟（均值）"
            value={fmtMs(s.dispatchLatencyMean)}
            subtitle={`${s.dispatchLatencyCount} 次`}
            icon={CheckmarkCircle01Icon}
            tone={s.dispatchLatencyMean > 1000 ? "warning" : "success"}
          />
          <MetricCard
            label="下发延迟（均值）"
            value={fmtMs(s.deliveryLatencyMean)}
            subtitle={`${s.deliveryLatencyCount} 次`}
            icon={SignalIcon}
            tone={s.deliveryLatencyMean > 2000 ? "warning" : "success"}
          />
          <MetricCard
            label="队列深度"
            value={String(s.queueDepth)}
            icon={ArrowRight01Icon}
            tone={s.queueDepth > 50 ? "warning" : "default"}
          />
          <MetricCard
            label="最长等待"
            value={fmtSeconds(s.oldestAgeSeconds)}
            icon={HourglassIcon}
            tone={s.oldestAgeSeconds > 300 ? "destructive" : s.oldestAgeSeconds > 120 ? "warning" : "default"}
          />
        </div>
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <MetricCard
            label="总派发数"
            value={String(s.totalDispatches)}
            icon={SatelliteIcon}
          />
          <MetricCard
            label="调度轮次耗时"
            value={fmtMs(s.roundDurationMean)}
            subtitle={`${s.totalClaimRounds} 轮`}
            icon={CpuIcon}
          />
          <MetricCard
            label="空抢率"
            value={ratioPct(s.emptyClaimRounds, s.totalClaimRounds)}
            subtitle={`${s.emptyClaimRounds} / ${s.totalClaimRounds}`}
            icon={Alert01Icon}
            tone={emptyRate > 0.5 ? "warning" : "default"}
          />
          <MetricCard
            label="事件 vs 轮询"
            value={`${s.wakeEvents} : ${s.wakePolls}`}
            subtitle={`事件占比 ${ratioPct(s.wakeEvents, wakeTotal)}`}
            icon={Activity02Icon}
            tone={s.wakePolls > s.wakeEvents ? "warning" : "info"}
          />
        </div>
      </section>

      {/* ─── 第 2 层：资源与执行 ───────────────────────── */}
      <section className="flex flex-col gap-3">
        <div className="flex items-center gap-2">
          <HugeiconsIcon icon={CpuIcon} className="size-4 text-muted-foreground" />
          <h2 className="text-sm font-semibold tracking-tight text-muted-foreground uppercase">
            资源与执行
          </h2>
        </div>
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <MetricCard
            label="槽位利用率"
            value={`${(s.slotUtilization * 100).toFixed(1)}%`}
            icon={SatelliteIcon}
            tone={s.slotUtilization > 0.8 ? "warning" : s.slotUtilization > 0.5 ? "info" : "default"}
          />
          <MetricCard
            label="槽位碎片率"
            value={`${(s.slotFragmentation * 100).toFixed(1)}%`}
            icon={Alert01Icon}
            tone={s.slotFragmentation > 0.5 ? "destructive" : "default"}
          />
          <MetricCard
            label="任务执行耗时"
            value={fmtMs(s.taskDurationMean)}
            subtitle={`${s.taskCompletedCount} 次完成`}
            icon={TimeManagementIcon}
          />
          <MetricCard
            label="租约回收次数"
            value={String(s.leaseReclaims)}
            icon={CheckmarkCircle01Icon}
            tone={s.leaseReclaims > 0 ? "warning" : "success"}
          />
        </div>
      </section>

      {/* ─── 第 3 层：管道健康 ──────────────────────────── */}
      <section className="flex flex-col gap-3">
        <div className="flex items-center gap-2">
          <HugeiconsIcon icon={SignalIcon} className="size-4 text-muted-foreground" />
          <h2 className="text-sm font-semibold tracking-tight text-muted-foreground uppercase">
            管道健康
          </h2>
        </div>
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          <MetricCard
            label="日志流积压"
            value={String(s.logStreamBacklog)}
            subtitle="行"
            icon={ArrowRight01Icon}
          />
          <MetricCard
            label="SSE 连接数"
            value={String(s.sseConnections)}
            icon={SignalIcon}
          />
          <Card>
            <CardContent className="pt-5 flex flex-col items-center justify-center gap-1 min-h-[88px]">
              <span className="text-xs text-muted-foreground">百分位延迟详情</span>
              <span className="text-[10px] text-muted-foreground/60 text-center">
                p50/p99/p999 经 Prometheus + Grafana 查看
                <br />
                或访问 /actuator/metrics
              </span>
            </CardContent>
          </Card>
        </div>
      </section>
    </DwScroll>
  )
}
