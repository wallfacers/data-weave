"use client"

import { useEffect, useState, useRef } from "react"

// —— 严重度徽标 ——
export function SeverityBadge({
  severity,
  t,
}: {
  severity: string
  t: ReturnType<typeof import("next-intl").useTranslations<"incidents">>
}) {
  const severityMap: Record<string, { bg: string; text: string }> = {
    CRITICAL: { bg: "bg-destructive/10", text: "text-destructive" },
    HIGH: { bg: "bg-destructive/10", text: "text-destructive" },
    MEDIUM: { bg: "bg-warning/10", text: "text-warning" },
    LOW: { bg: "bg-info/10", text: "text-info" },
    WARNING: { bg: "bg-warning/10", text: "text-warning" },
  }
  const s = severityMap[severity] ?? { bg: "bg-muted", text: "text-muted-foreground" }
  return (
    <span className={`inline-flex items-center rounded px-1.5 py-0.5 text-xs font-medium ${s.bg} ${s.text}`}>
      {t(`severity.${severity}` as never)}
    </span>
  )
}

// —— 状态徽标 ——
export function StateBadge({
  state,
  t,
}: {
  state: string
  t: ReturnType<typeof import("next-intl").useTranslations<"incidents">>
}) {
  const stateMap: Record<string, string> = {
    OPEN: "text-destructive",
    MITIGATING: "text-warning",
    RESOLVED: "text-success",
    SUPPRESSED: "text-muted-foreground",
    CLOSED: "text-muted-foreground",
  }
  const color = stateMap[state] ?? "text-muted-foreground"
  return (
    <span className={`text-xs font-medium ${color}`}>
      {t(`state.${state}` as never)}
    </span>
  )
}

// —— 爆炸半径徽标（null=血缘不可用, 0=无下游影响, >0=影响 N 个下游）——
export function BlastRadiusBadge({
  blastRadius,
  t,
}: {
  blastRadius: number | null
  t: ReturnType<typeof import("next-intl").useTranslations<"incidents">>
}) {
  if (blastRadius === null) {
    return (
      <span className="inline-flex items-center gap-1 text-muted-foreground/60">
        <svg className="size-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
          <circle cx="12" cy="12" r="10" />
          <line x1="12" y1="8" x2="12" y2="12" />
          <line x1="12" y1="16" x2="12.01" y2="16" />
        </svg>
        {t("card.blastRadiusNull")}
      </span>
    )
  }
  if (blastRadius === 0) {
    return (
      <span className="inline-flex items-center gap-1 text-muted-foreground/60">
        <svg className="size-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
          <circle cx="12" cy="12" r="10" />
          <path d="M9 12l2 2 4-4" />
        </svg>
        {t("card.blastRadiusZero")}
      </span>
    )
  }
  return (
    <span className="inline-flex items-center gap-1 text-muted-foreground">
      <svg className="size-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
        <circle cx="12" cy="12" r="10" />
        <path d="M12 6v6l4 2" />
      </svg>
      {t("card.blastRadius", { count: blastRadius })}
    </span>
  )
}

// —— SLA 倒计时（客户端每秒刷新、过期转"已超期"红色态）——
export function SlaCountdown({
  timeBudgetAt,
  t,
}: {
  timeBudgetAt: string | null
  t: ReturnType<typeof import("next-intl").useTranslations<"incidents">>
}) {
  const [, setTick] = useState(0)
  const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null)

  useEffect(() => {
    if (timeBudgetAt == null) return
    intervalRef.current = setInterval(() => setTick((n) => n + 1), 1000)
    return () => {
      if (intervalRef.current) clearInterval(intervalRef.current)
    }
  }, [timeBudgetAt])

  if (timeBudgetAt === null) {
    return (
      <span className="inline-flex items-center gap-1 text-muted-foreground/60">
        <svg className="size-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
          <circle cx="12" cy="12" r="10" />
          <polyline points="12,6 12,12 16,14" />
        </svg>
        {t("card.timeBudgetNoSla")}
      </span>
    )
  }

  const target = new Date(timeBudgetAt).getTime()
  const now = Date.now()
  const diffMs = target - now

  if (diffMs <= 0) {
    const overdueMin = Math.ceil(Math.abs(diffMs) / 60000)
    const overdueStr = overdueMin < 60
      ? `${overdueMin}m`
      : `${Math.floor(overdueMin / 60)}h${overdueMin % 60}m`
    return (
      <span className="inline-flex items-center gap-1 text-destructive">
        <svg className="size-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
          <circle cx="12" cy="12" r="10" />
          <polyline points="12,6 12,12 16,14" />
        </svg>
        {t("card.timeBudgetOverdue", { time: overdueStr })}
      </span>
    )
  }

  const hours = Math.floor(diffMs / 3600000)
  const minutes = Math.floor((diffMs % 3600000) / 60000)
  const seconds = Math.floor((diffMs % 60000) / 1000)
  const countdownStr = hours > 0
    ? `${hours}h${minutes}m${seconds}s`
    : `${minutes}m${seconds}s`

  return (
    <span className="inline-flex items-center gap-1 text-muted-foreground">
      <svg className="size-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
        <circle cx="12" cy="12" r="10" />
        <polyline points="12,6 12,12 16,14" />
      </svg>
      {t("card.countdown", { time: countdownStr })}
    </span>
  )
}

// —— 历史发生 N 次 ——
export function PriorCountBadge({
  count,
  t,
}: {
  count: number
  t: ReturnType<typeof import("next-intl").useTranslations<"incidents">>
}) {
  if (!count || count <= 0) return null
  return (
    <span className="inline-flex items-center gap-1 text-xs text-muted-foreground/70">
      {t("card.priorCount", { count })}
    </span>
  )
}

// —— 诊断占位态（"等待运维编队接入"，非错误样式）——
export function DiagnosisPlaceholder({
  t,
}: {
  t: ReturnType<typeof import("next-intl").useTranslations<"incidents">>
}) {
  return (
    <span className="inline-flex items-center gap-1 text-xs text-muted-foreground/50 italic">
      <svg className="size-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
        <path d="M9.663 17h4.673M12 3v1m6.364 1.636l-.707.707M21 12h-1M4 12H3m3.343-5.657l-.707-.707m2.828 9.9a5 5 0 117.072 0l-.548.547A3.374 3.374 0 0114 18.469V19a2 2 0 11-4 0v-.531c0-.895-.356-1.754-.988-2.386l-.548-.547z" />
      </svg>
      {t("card.diagnosisPlaceholder")}
    </span>
  )
}

// —— 提案占位态 ——
export function ProposalPlaceholder({
  t,
}: {
  t: ReturnType<typeof import("next-intl").useTranslations<"incidents">>
}) {
  return (
    <span className="inline-flex items-center gap-1 text-xs text-muted-foreground/50 italic">
      <svg className="size-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
        <path d="M9 12h6m-3-3v6m-7 5h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v12a2 2 0 002 2z" />
      </svg>
      {t("card.proposalPlaceholder")}
    </span>
  )
}
