"use client"

import { useLocale, useTranslations } from "next-intl"
import { useMemo, useState } from "react"
import { format, subDays, type Locale } from "date-fns"
import { zhCN, enUS } from "date-fns/locale"

import { Card, CardContent } from "@/components/ui/card"
import { DatePicker } from "@/components/ui/date-picker"
import { type MetricCard } from "@/lib/types"
import { fetchApi, useLiveData } from "@/lib/workspace/use-api"
import { useProjectContext } from "@/lib/project-context"
import type { ViewProps } from "@/lib/workspace/registry"
import { ViewRefreshControl } from "./view-refresh-control"
import { ViewStatus } from "./view-status"
import { DwScroll } from "@/components/ui/dw-scroll"

/** 业务报表（最小版）：当前项目的指标卡片网格（名称 / 口径版本 / 最新值或空态）。 */
export function ReportsView({ active }: ViewProps) {
  const t = useTranslations("reports")
  const locale = useLocale()
  const dateLocale: Locale = useMemo(() => (locale === "zh-CN" ? zhCN : enUS), [locale])
  const [autoEnabled, setAutoEnabled] = useState(true)
  // 036 FR-012：指标按当前项目隔离；响应式读取，切项目即重取
  const projectId = useProjectContext((s) => s.currentProjectId)
  // 业务日期观察（复用 ops 的 bizDate 模型：T-1 兜底、yyyy-MM-dd），切日期重取对应快照
  const [bizDate, setBizDate] = useState(() => format(subDays(new Date(), 1), "yyyy-MM-dd"))

  const url = projectId != null ? `/api/metrics?projectId=${projectId}&bizDate=${bizDate}` : ""
  const { data: metrics, loading, refreshing, stale, lastUpdatedAt, refresh } = useLiveData<MetricCard[]>(
    () => (url ? fetchApi<MetricCard[]>(url) : Promise.resolve([] as MetricCard[])),
    { active, enabled: autoEnabled, deps: [url] },
  )

  if (projectId == null) {
    return (
      <DwScroll className="flex-1" innerClassName="flex flex-col items-center justify-center gap-2 p-10 text-center">
        <p className="text-sm text-muted-foreground">{t("noProject")}</p>
      </DwScroll>
    )
  }

  if (metrics == null) return <ViewStatus loading={loading} />

  return (
    <DwScroll className="flex-1" innerClassName="flex flex-col gap-5 p-5">
      {/* Header */}
      <div className="shrink-0 flex items-center justify-between">
        <p className="text-xs text-muted-foreground">{t("subtitle")}</p>
        <div className="flex items-center gap-3">
          {/* 业务日期观察（T-1 兜底；切日期按 (projectId, bizDate) 收敛到当日快照） */}
          <DatePicker
            value={bizDate}
            onChange={setBizDate}
            triggerClassName="h-8 w-[150px]"
            locale={dateLocale}
            quickLabels={{ today: t("today"), yesterday: t("yesterday") }}
          />
          <ViewRefreshControl
            lastUpdatedAt={lastUpdatedAt}
            refreshing={refreshing}
            stale={stale}
            autoEnabled={autoEnabled}
            onToggleAuto={setAutoEnabled}
            onRefresh={refresh}
          />
        </div>
      </div>

      {metrics.length === 0 ? (
        <div className="flex flex-1 items-center justify-center p-10 text-center">
          <p className="text-muted-foreground">
            {t("empty")}
          </p>
        </div>
      ) : (
        <div className="grid gap-2.5 sm:grid-cols-2 xl:grid-cols-3">
          {metrics.map((m) => (
            <Card key={m.id}>
              <CardContent className="flex flex-col gap-1 pt-4">
                <div className="flex items-center justify-between gap-2">
                  <span className="truncate text-sm font-medium">{m.name}</span>
                  <span className="shrink-0 font-mono text-[10px] text-muted-foreground">v{m.versionNo}</span>
                </div>
                {m.value != null ? (
                  <span className="text-2xl font-semibold tracking-tight font-sans tabular-nums">
                    {String(m.value)}
                    {m.unit && (
                      <span className="ml-1 text-xs font-normal text-muted-foreground">
                        {m.unit}
                      </span>
                    )}
                  </span>
                ) : (
                  <span className="text-sm text-muted-foreground">{t("noData")}</span>
                )}
                <span className="font-mono text-xs text-muted-foreground">{m.code}</span>
              </CardContent>
            </Card>
          ))}
        </div>
      )}
    </DwScroll>
  )
}
