"use client"

import { useTranslations } from "next-intl"
import { useState } from "react"
import { HugeiconsIcon } from "@hugeicons/react"
import { Analytics01Icon } from "@hugeicons/core-free-icons"
import { format, subDays } from "date-fns"

import { Badge } from "@/components/ui/badge"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
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
    <DwScroll className="flex-1" innerClassName="flex flex-col gap-6 p-6 md:p-10">
      <div className="flex items-center justify-between">
        <div className="flex flex-col gap-1">
          <div className="flex items-center gap-2">
            <HugeiconsIcon icon={Analytics01Icon} className="size-5 text-primary" />
            <h1 className="text-2xl font-semibold tracking-tight">{t("title")}</h1>
          </div>
          <p className="text-sm text-muted-foreground">
            {t("subtitle")}
          </p>
        </div>
        <div className="flex items-center gap-3">
          {/* 业务日期观察（T-1 兜底；切日期按 (projectId, bizDate) 收敛到当日快照） */}
          <div className="flex flex-col gap-1">
            <span className="text-[10px] text-muted-foreground">{t("bizDate")}</span>
            <DatePicker
              value={bizDate}
              onChange={setBizDate}
              className="w-[150px]"
              triggerClassName="h-8 w-[150px]"
              showQuickActions
              quickLabels={{ today: t("today"), yesterday: t("yesterday") }}
            />
          </div>
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
        <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
          {metrics.map((m) => (
            <Card key={m.id}>
              <CardHeader>
                <CardTitle className="flex items-center justify-between gap-2 text-sm font-medium">
                  <span className="truncate">{m.name}</span>
                  <Badge variant="secondary" className="font-mono">
                    v{m.versionNo}
                  </Badge>
                </CardTitle>
              </CardHeader>
              <CardContent className="flex flex-col gap-1">
                {m.value != null ? (
                  <span className="text-3xl font-semibold tracking-tight font-sans tabular-nums">
                    {String(m.value)}
                    {m.unit && (
                      <span className="ml-1 text-sm font-normal text-muted-foreground">
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
