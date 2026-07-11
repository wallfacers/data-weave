"use client"

/**
 * 信号流面板 —— 监督席"信号流"Tab 内容。
 *
 * 展示与工单关联的系统异常信号（HealthEvent），按时间倒序排列，
 * 支持按事件类型和严重度筛选，15s 自动刷新。
 *
 * 设计约束（DESIGN.md）：语义 token、DwScroll、Card、Badge 语义变体、
 * LoadingState、DropdownSelect、无分割线、不手写 dark:。
 */

import { useCallback, useEffect, useMemo, useState } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import { Alert02Icon } from "@hugeicons/core-free-icons"
import { DropdownSelect, type DropdownOption } from "@/components/ui/select"
import { DwScroll } from "@/components/ui/dw-scroll"
import { Card, CardContent } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { LoadingState } from "@/components/workspace/shared/loading-state"
import { ViewRefreshControl } from "@/components/workspace/views/view-refresh-control"
import { fetchEvents, type HealthEvent } from "@/lib/event-center-api"

const EVENT_TYPES = [
  "TASK_FAILED", "TASK_TIMEOUT", "SLA_BREACH",
  "NODE_OFFLINE", "WORKFLOW_STATE", "QUALITY_FAILED", "METRIC_BREACH",
]
const SEVERITIES = ["INFO", "LOW", "MEDIUM", "WARNING", "HIGH", "CRITICAL"]

type EventCtx = Record<string, unknown>

function parseCtx(json?: string): EventCtx {
  if (!json) return {}
  try {
    const v: unknown = JSON.parse(json)
    return v && typeof v === "object" ? (v as EventCtx) : {}
  } catch {
    return {}
  }
}

function ctxStr(ctx: EventCtx, key: string): string | undefined {
  const v = ctx[key]
  return v === null || v === undefined ? undefined : String(v)
}

function severityBadgeVariant(sev?: string): "destructive" | "warning" | "info" | "secondary" {
  switch ((sev || "").toUpperCase()) {
    case "CRITICAL":
    case "HIGH":
      return "destructive"
    case "WARNING":
    case "MEDIUM":
      return "warning"
    default:
      return "secondary"
  }
}

export interface SignalStreamPanelProps {
  active: boolean
}

export function SignalStreamPanel({ active }: SignalStreamPanelProps) {
  const t = useTranslations("signalStream")
  const [events, setEvents] = useState<HealthEvent[]>([])
  const [total, setTotal] = useState(0)
  const [typeFilter, setTypeFilter] = useState("")
  const [sevFilter, setSevFilter] = useState("")
  const [loading, setLoading] = useState(false)
  const [initialLoad, setInitialLoad] = useState(true)
  const [autoEnabled, setAutoEnabled] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [lastUpdatedAt, setLastUpdatedAt] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [expandedId, setExpandedId] = useState<number | null>(null)

  const loadEvents = useCallback(async (isRefresh = false) => {
    if (isRefresh) setRefreshing(true)
    else setLoading(true)
    setError(null)
    try {
      const r = await fetchEvents({
        type: typeFilter || undefined,
        severity: sevFilter || undefined,
        size: 50,
        incidentOnly: true,
      } as Record<string, unknown> & { type?: string; severity?: string; size?: number })
      setEvents(r.items)
      setTotal(r.total)
      setLastUpdatedAt(Date.now())
    } catch {
      setError("loadError")
    } finally {
      setLoading(false)
      setRefreshing(false)
      setInitialLoad(false)
    }
  }, [typeFilter, sevFilter])

  // 自动刷新 15s
  useEffect(() => {
    if (!active || !autoEnabled) return
    const id = setInterval(() => loadEvents(true), 15_000)
    return () => clearInterval(id)
  }, [active, autoEnabled, loadEvents])

  // 首次加载 + 筛选变化
  useEffect(() => {
    if (active) loadEvents(false)
  }, [active, typeFilter, sevFilter]) // eslint-disable-line react-hooks/exhaustive-deps

  const eventTypeOptions = useMemo<DropdownOption[]>(
    () => [
      { value: "", label: t("filter.allTypes") },
      ...EVENT_TYPES.map((ty) => ({ value: ty, label: t(`type.${ty}` as never) })),
    ],
    [t],
  )

  const severityOptions = useMemo<DropdownOption[]>(
    () => [
      { value: "", label: t("filter.allSeverities") },
      ...SEVERITIES.map((s) => ({ value: s, label: t(`severity.${s}` as never) })),
    ],
    [t],
  )

  const buildSummary = (e: HealthEvent): string => {
    const ctx = parseCtx(e.contextJson)
    const name = e.refName || ctxStr(ctx, "taskName") || ctxStr(ctx, "workflowName") || e.refId || `#${e.id}`
    switch (e.type) {
      case "TASK_FAILED": {
        const reason = ctxStr(ctx, "failureReason")
        return reason
          ? `${t("summary.TASK_FAILED", { name })}（${reason}）`
          : t("summary.TASK_FAILED", { name })
      }
      case "TASK_TIMEOUT":
        return t("summary.TASK_TIMEOUT", { name })
      case "SLA_BREACH":
        return t("summary.SLA_BREACH", { name, minutes: ctxStr(ctx, "breachMinutes") || "0" })
      case "NODE_OFFLINE":
        return t("summary.NODE_OFFLINE", { node: ctxStr(ctx, "workerNodeCode") || name })
      case "QUALITY_FAILED":
        return t("summary.QUALITY_FAILED", { dataset: name, message: ctxStr(ctx, "message") || "" })
      case "METRIC_BREACH":
        return t("summary.METRIC_BREACH", { name })
      default:
        return e.summary || e.type
    }
  }

  // ── 渲染 ────────────────────────────────────────────────

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      {/* Toolbar */}
      <div className="shrink-0 flex flex-wrap items-center gap-2 px-(--card-spacing) pt-(--card-spacing)">
        <DropdownSelect
          value={typeFilter}
          onChange={setTypeFilter}
          options={eventTypeOptions}
          className="w-36"
        />
        <DropdownSelect
          value={sevFilter}
          onChange={setSevFilter}
          options={severityOptions}
          className="w-36"
        />
        <span className="ml-auto text-xs text-muted-foreground">
          {t("filter.total", { count: total }) || `共 ${total} 条`}
        </span>
        <ViewRefreshControl
          lastUpdatedAt={lastUpdatedAt}
          refreshing={refreshing}
          stale={false}
          autoEnabled={autoEnabled}
          onToggleAuto={setAutoEnabled}
          onRefresh={() => loadEvents(true)}
        />
      </div>

      {/* Body */}
      {initialLoad && loading ? (
        <LoadingState />
      ) : error ? (
        <div className="flex flex-1 flex-col items-center justify-center gap-2 text-muted-foreground py-12">
          <HugeiconsIcon icon={Alert02Icon} className="size-8" />
          <p className="text-sm">{t(error as never)}</p>
          <button
            onClick={() => loadEvents(false)}
            className="text-xs text-link hover:underline"
          >
            {t("filter.retry") || "重试"}
          </button>
        </div>
      ) : events.length === 0 ? (
        <div className="flex flex-1 flex-col items-center justify-center gap-2 text-muted-foreground">
          <HugeiconsIcon icon={Alert02Icon} className="size-8" />
          <p className="text-sm">{t("empty")}</p>
        </div>
      ) : (
        <DwScroll className="flex-1" innerClassName="flex flex-col gap-2 p-(--card-spacing)">
          {events.map((e) => {
            const isExpanded = expandedId === e.id
            return (
              <Card key={e.id} size="sm">
                <CardContent
                  className="cursor-pointer"
                  onClick={() => setExpandedId(isExpanded ? null : e.id)}
                >
                  <div className="flex items-start gap-3">
                    <Badge variant={severityBadgeVariant(e.severity)} className="shrink-0 mt-0.5">
                      {e.severity ? t(`severity.${e.severity}` as never) : "—"}
                    </Badge>
                    <div className="min-w-0 flex-1">
                      <div className="flex items-center gap-2">
                        <Badge variant="secondary" className="text-xs">
                          {t(`type.${e.type}` as never)}
                        </Badge>
                        {e.count > 1 && (
                          <span className="text-xs text-muted-foreground">×{e.count}</span>
                        )}
                      </div>
                      <p className="mt-1 truncate text-sm">{buildSummary(e)}</p>
                      <p className="mt-0.5 text-xs text-muted-foreground tabular-nums">
                        {e.lastOccurredAt}
                      </p>
                    </div>
                  </div>

                  {/* 展开的原始 JSON */}
                  {isExpanded && (
                    <div className="mt-3 rounded-md border bg-muted/30 p-3">
                      {e.contextJson ? (
                        <pre className="text-xs font-mono whitespace-pre-wrap break-all text-muted-foreground">
                          {(() => {
                            try {
                              return JSON.stringify(JSON.parse(e.contextJson), null, 2)
                            } catch {
                              return e.contextJson
                            }
                          })()}
                        </pre>
                      ) : (
                        <p className="text-xs text-muted-foreground">{t("detail.empty")}</p>
                      )}
                    </div>
                  )}
                </CardContent>
              </Card>
            )
          })}
        </DwScroll>
      )}
    </div>
  )
}
