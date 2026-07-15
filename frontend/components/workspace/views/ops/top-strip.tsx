"use client"

/**
 * 运维中心顶条：大盘统计（总 / 运行中 / 成功 / 失败 / SLA 风险）。
 * 040：支持按 biz_date 单日筛选。
 *
 * 数据来源：GET /api/ops/summary（DashboardSummary）+ GET /api/ops/eta-summary（ETA 计数）。
 * 后端未起时 graceful fallback 到全 0，不抛错。
 */

import { useTranslations, useLocale } from "next-intl"
import { useState, useMemo } from "react"
import { format } from "date-fns"
import { zhCN, enUS } from "date-fns/locale"
import { HugeiconsIcon, type IconSvgElement } from "@hugeicons/react"
import {
  Alert01Icon,
  CheckmarkCircle01Icon,
  Loading03Icon,
  TimeManagementIcon,
  Layers01Icon,
} from "@hugeicons/core-free-icons"

import { useLiveData } from "@/lib/workspace/use-api"
import { ViewRefreshControl } from "../view-refresh-control"
import { useProjectContext } from "@/lib/project-context"
import { DatePicker } from "@/components/ui/date-picker"
import type { DashboardSummary } from "@/lib/types"

interface EtaSummary {
  riskCount?: number
}

type Tone = "default" | "success" | "destructive" | "warning" | "info"

const TONE_CLASSES: Record<Tone, string> = {
  default: "bg-muted text-muted-foreground",
  success: "bg-success/10 text-success",
  destructive: "bg-destructive/10 text-destructive",
  warning: "bg-warning/10 text-warning",
  info: "bg-info/10 text-info",
}

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

export function OpsTopStrip({ active }: { active?: boolean }) {
  const t = useTranslations("ops")
  const locale = useLocale()
  const dateFnsLocale = useMemo(() => (locale === "zh-CN" ? zhCN : enUS), [locale])
  const [autoEnabled, setAutoEnabled] = useState(true)
  const projectId = useProjectContext((s) => s.currentProjectId) ?? 1

  // 040: 默认选中昨天的 bizDate（T-1）
  const yesterdayStr = useMemo(() => {
    const d = new Date()
    d.setDate(d.getDate() - 1)
    return format(d, "yyyy-MM-dd")
  }, [])
  const [bizDate, setBizDate] = useState<string>(yesterdayStr)

  const summaryUrl = `/api/ops/summary?projectId=${projectId}&bizDate=${bizDate}`
  const { data: summary, refreshing: r1, stale: s1, lastUpdatedAt: lu1, refresh: ref1 } = useLiveData<DashboardSummary>(summaryUrl, { active, enabled: autoEnabled })
  const { data: eta, refreshing: r2, stale: s2, lastUpdatedAt: lu2, refresh: ref2 } = useLiveData<EtaSummary>(`/api/ops/eta-summary?projectId=${projectId}`, { active, enabled: autoEnabled })

  const total = summary?.total ?? 0
  const running = summary?.running ?? 0
  const success = summary?.success ?? 0
  const failed = summary?.failed ?? 0
  const slaRisk = eta?.riskCount ?? 0

  const lastUpdatedAt = lu1 != null && lu2 != null ? Math.max(lu1, lu2) : lu1 ?? lu2

  const handleRefresh = () => { ref1(); ref2() }

  return (
    <div className="p-5">
      <div className="flex items-center justify-between mb-3">
        <span className="text-xs text-muted-foreground">{t("subtitle")}</span>
        <div className="flex items-center gap-3">
          <DatePicker
            value={bizDate}
            onChange={(d) => { if (d) setBizDate(d) }}
            triggerClassName="h-8 w-36"
            locale={dateFnsLocale}
            quickLabels={{ today: t("quickToday"), yesterday: t("quickYesterday") }}
            disableClear
          />
          <ViewRefreshControl
            lastUpdatedAt={lastUpdatedAt}
            refreshing={r1 || r2}
            stale={s1 || s2}
            autoEnabled={autoEnabled}
            onToggleAuto={setAutoEnabled}
            onRefresh={handleRefresh}
          />
        </div>
      </div>
      <div className="grid gap-3 grid-cols-2 sm:grid-cols-3 2xl:grid-cols-5">
        <TopStat label={t("topTotal")} value={String(total)} icon={Layers01Icon} tone="default" />
        <TopStat label={t("topRunning")} value={String(running)} icon={Loading03Icon} tone="info" />
        <TopStat label={t("topSuccess")} value={String(success)} icon={CheckmarkCircle01Icon} tone="success" />
        <TopStat label={t("topFailed")} value={String(failed)} icon={Alert01Icon} tone={failed > 0 ? "destructive" : "default"} />
        <TopStat label={t("topSlaRisk")} value={String(slaRisk)} icon={TimeManagementIcon} tone={slaRisk > 0 ? "warning" : "default"} />
      </div>
    </div>
  )
}
