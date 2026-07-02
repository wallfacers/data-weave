"use client"

import { useCallback, useEffect, useMemo, useState } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  Alert02Icon,
  ArrowRight01Icon,
  BellIcon,
  Delete01Icon,
  PlusSignIcon,
  RefreshIcon,
} from "@hugeicons/core-free-icons"
import { DropdownSelect, type DropdownOption } from "@/components/ui/select"
import { useWorkspaceStore } from "@/lib/workspace/store"
import {
  createSubscription,
  deleteSubscription,
  fetchChannels,
  fetchEvents,
  fetchSubscriptions,
  refKindToView,
  type AlertChannelLite,
  type EventSubscription,
  type HealthEvent,
} from "@/lib/event-center-api"
import { useMinSpin } from "@/hooks/use-min-spin"
import { cn } from "@/lib/utils"

const EVENT_TYPES = [
  "SLA_BREACH",
  "QUALITY_FAILED",
  "TASK_FAILED",
  "TASK_TIMEOUT",
  "WORKFLOW_STATE",
  "NODE_OFFLINE",
  "METRIC_BREACH",
  "ASSET_CHANGED",
]
const SEVERITIES = ["INFO", "LOW", "MEDIUM", "WARNING", "HIGH", "CRITICAL"]

type TabKey = "events" | "subscriptions"
type EventCtx = Record<string, unknown>

function severityClass(sev?: string): string {
  switch ((sev || "").toUpperCase()) {
    case "CRITICAL":
    case "HIGH":
      return "text-destructive"
    case "WARNING":
    case "MEDIUM":
      return "text-warning"
    default:
      return "text-muted-foreground"
  }
}

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

/** 按事件类型 + contextJson 拼装本地化的可读描述；未知类型/解析失败兜底为后端 summary。 */
function buildEventSummary(e: HealthEvent, t: ReturnType<typeof useTranslations>): string {
  const ctx = parseCtx(e.contextJson)
  const name = e.refName || ctxStr(ctx, "taskName") || ctxStr(ctx, "workflowName") || e.refId
  const fallbackName = name || `#${e.refId || e.fingerprint}`
  switch (e.type) {
    case "TASK_FAILED": {
      const base = t("summary.TASK_FAILED", { name: fallbackName })
      const reason = ctxStr(ctx, "failureReason")
      return reason ? `${base}（${reason}）` : base
    }
    case "TASK_TIMEOUT":
      return t("summary.TASK_TIMEOUT", { name: fallbackName })
    case "WORKFLOW_STATE":
      return ctxStr(ctx, "state") === "STOPPED"
        ? t("summary.WORKFLOW_STATE_STOPPED", { name: fallbackName })
        : t("summary.WORKFLOW_STATE_FAILED", { name: fallbackName })
    case "SLA_BREACH":
      return t("summary.SLA_BREACH", {
        name: fallbackName,
        minutes: ctxStr(ctx, "breachMinutes") || "0",
        bizDate: ctxStr(ctx, "bizDate") || "",
      })
    case "NODE_OFFLINE":
      return t("summary.NODE_OFFLINE", { node: ctxStr(ctx, "workerNodeCode") || e.refId || "" })
    case "QUALITY_FAILED":
      return t("summary.QUALITY_FAILED", {
        dataset: ctxStr(ctx, "datasetRef") || fallbackName,
        message: ctxStr(ctx, "message") || "",
      })
    case "ASSET_CHANGED":
      return t("summary.ASSET_CHANGED", {
        name: fallbackName,
        changeType: ctxStr(ctx, "changeType") || "",
      })
    case "METRIC_BREACH":
      return t("summary.METRIC_BREACH", { name: fallbackName })
    default:
      return e.summary || e.fingerprint
  }
}

export function EventCenterView() {
  const t = useTranslations("eventCenter")
  const open = useWorkspaceStore((s) => s.open)

  const [tab, setTab] = useState<TabKey>("events")
  const [events, setEvents] = useState<HealthEvent[]>([])
  const [total, setTotal] = useState(0)
  const [typeFilter, setTypeFilter] = useState("")
  const [sevFilter, setSevFilter] = useState("")
  const [loading, setLoading] = useState(false)

  const [subs, setSubs] = useState<EventSubscription[]>([])
  const [channels, setChannels] = useState<AlertChannelLite[]>([])
  const [newSub, setNewSub] = useState({ typeFilter: "", minSeverity: "", channelId: "" })

  const loadEvents = useCallback(async () => {
    setLoading(true)
    try {
      const r = await fetchEvents({ type: typeFilter || undefined, severity: sevFilter || undefined, size: 50 })
      setEvents(r.items)
      setTotal(r.total)
    } finally {
      setLoading(false)
    }
  }, [typeFilter, sevFilter])

  const loadSubs = useCallback(async () => {
    const [s, c] = await Promise.all([fetchSubscriptions(), fetchChannels()])
    setSubs(s)
    setChannels(c)
  }, [])

  // 刷新按钮旋转：跟随 loading，兜底最短一圈保证可见（同 ViewRefreshControl）。
  const spinning = useMinSpin(loading)

  useEffect(() => {
    if (tab === "events") loadEvents()
    else loadSubs()
  }, [tab, loadEvents, loadSubs])

  const onDeepLink = (e: HealthEvent) => {
    const view = refKindToView(e.refKind)
    if (view) open(view, e.refId ? { ref: e.refId } : undefined)
  }

  const onCreateSub = async () => {
    if (!newSub.channelId) return
    await createSubscription({
      typeFilter: newSub.typeFilter || undefined,
      minSeverity: newSub.minSeverity || undefined,
      channelId: Number(newSub.channelId),
    })
    setNewSub({ typeFilter: "", minSeverity: "", channelId: "" })
    loadSubs()
  }

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

  const subSeverityOptions = useMemo<DropdownOption[]>(
    () => [
      { value: "", label: t("sub.anySeverity") },
      ...SEVERITIES.map((s) => ({ value: s, label: t("severityGte", { level: t(`severity.${s}` as never) }) })),
    ],
    [t],
  )

  const channelOptions = useMemo<DropdownOption[]>(
    () => [
      { value: "", label: t("sub.selectChannel") },
      ...channels.map((c) => ({ value: String(c.id), label: `${c.name} (${c.type})` })),
    ],
    [channels, t],
  )

  const tabs: { key: TabKey; labelKey: string; icon: typeof Alert02Icon }[] = [
    { key: "events", labelKey: "tab.events", icon: Alert02Icon },
    { key: "subscriptions", labelKey: "tab.subscriptions", icon: BellIcon },
  ]

  return (
    <div className="flex h-full flex-col">
      {/* Tab bar — 下划线式，与 quality-view 同款 */}
      <div role="tablist">
        <div className="flex items-center gap-1 px-5 h-11">
          {tabs.map(tb => {
            const isActive = tab === tb.key
            return (
              <button
                key={tb.key}
                type="button"
                role="tab"
                aria-selected={isActive}
                onClick={() => setTab(tb.key)}
                className={
                  "relative flex items-center gap-1.5 px-3 py-1 text-sm transition-colors " +
                  (isActive
                    ? "font-medium text-foreground after:absolute after:inset-x-2 after:bottom-0 after:h-0.5 after:rounded-full after:bg-primary"
                    : "text-muted-foreground hover:text-foreground")
                }
              >
                <HugeiconsIcon icon={tb.icon} className="size-4" />
                {t(tb.labelKey as never)}
              </button>
            )
          })}
        </div>
        <div className="mx-5 border-b" />
      </div>

      <div className="flex min-h-0 flex-1 flex-col overflow-auto p-5">
      {tab === "events" ? (
        <>
          {/* filters */}
          <div className="flex flex-wrap items-center gap-2 mb-3">
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
            <button
              onClick={loadEvents}
              disabled={spinning}
              className="inline-flex items-center gap-1 rounded-md border border-border px-2 py-1 text-sm text-muted-foreground hover:text-foreground disabled:opacity-50"
            >
              <HugeiconsIcon icon={RefreshIcon} size={14} className={cn(spinning && "animate-spin")} />
              {t("refresh")}
            </button>
            <span className="ml-auto text-xs text-muted-foreground">{t("total", { count: total })}</span>
          </div>

          {/* timeline */}
          <div className="flex-1 overflow-auto">
            {events.length === 0 ? (
              <div className="flex h-full flex-col items-center justify-center gap-2 text-muted-foreground">
                <HugeiconsIcon icon={Alert02Icon} size={32} />
                <p className="text-sm">{t("empty")}</p>
              </div>
            ) : (
              <ul className="flex flex-col gap-2">
                {events.map((e) => (
                  <li
                    key={e.id}
                    className="flex items-start gap-3 rounded-lg border border-border bg-card p-3"
                  >
                    <span className={`mt-0.5 text-xs font-medium ${severityClass(e.severity)}`}>
                      {e.severity ? t(`severity.${e.severity}` as never) : "—"}
                    </span>
                    <div className="min-w-0 flex-1">
                      <div className="flex items-center gap-2">
                        <span className="rounded bg-muted px-1.5 py-0.5 text-xs text-muted-foreground">
                          {t(`type.${e.type}` as never)}
                        </span>
                        {e.count > 1 && (
                          <span className="text-xs text-muted-foreground">×{e.count}</span>
                        )}
                      </div>
                      <p className="mt-1 truncate text-sm text-foreground">{buildEventSummary(e, t)}</p>
                      <p className="mt-0.5 text-xs text-muted-foreground">{e.lastOccurredAt}</p>
                    </div>
                    {refKindToView(e.refKind) && (
                      <button
                        onClick={() => onDeepLink(e)}
                        title={t("openRef", { kind: t(`refKind.${e.refKind}` as never) })}
                        className="inline-flex items-center gap-1 rounded-md px-2 py-1 text-xs text-primary hover:underline"
                      >
                        {t(`refKind.${e.refKind}` as never)}
                        <HugeiconsIcon icon={ArrowRight01Icon} size={12} />
                      </button>
                    )}
                  </li>
                ))}
              </ul>
            )}
          </div>
        </>
      ) : (
        <div className="flex flex-1 flex-col gap-2 overflow-auto">
          {/* create subscription */}
          <div className="flex flex-wrap items-end gap-2">
            <DropdownSelect
              value={newSub.typeFilter}
              onChange={(v) => setNewSub({ ...newSub, typeFilter: v })}
              options={eventTypeOptions}
              className="w-36"
            />
            <DropdownSelect
              value={newSub.minSeverity}
              onChange={(v) => setNewSub({ ...newSub, minSeverity: v })}
              options={subSeverityOptions}
              className="w-36"
            />
            <DropdownSelect
              value={newSub.channelId}
              onChange={(v) => setNewSub({ ...newSub, channelId: v })}
              options={channelOptions}
              className="w-44"
            />
            <button
              onClick={onCreateSub}
              disabled={!newSub.channelId}
              className="inline-flex items-center gap-1 rounded-md bg-primary px-3 py-1 text-sm text-primary-foreground disabled:opacity-50"
            >
              <HugeiconsIcon icon={PlusSignIcon} size={14} />
              {t("sub.create")}
            </button>
          </div>

          {/* subscription list */}
          {subs.length === 0 ? (
            <div className="flex flex-1 flex-col items-center justify-center gap-2 text-muted-foreground">
              <HugeiconsIcon icon={BellIcon} size={32} />
              <p className="text-sm">{t("sub.empty")}</p>
            </div>
          ) : (
            <ul className="flex flex-col gap-2">
              {subs.map((s) => (
                <li
                  key={s.id}
                  className="flex items-center gap-3 rounded-lg border border-border bg-card p-3 text-sm"
                >
                  <span className="rounded bg-muted px-1.5 py-0.5 text-xs">
                    {s.typeFilter ? t(`type.${s.typeFilter}` as never) : t("filter.allTypes")}
                  </span>
                  {s.minSeverity && <span className="text-xs text-muted-foreground">≥ {s.minSeverity}</span>}
                  <span className="text-muted-foreground">
                    → {channels.find((c) => c.id === s.channelId)?.name || `#${s.channelId}`}
                  </span>
                  <button
                    onClick={async () => {
                      await deleteSubscription(s.id)
                      loadSubs()
                    }}
                    className="ml-auto inline-flex items-center gap-1 rounded-md px-2 py-1 text-xs text-destructive hover:underline"
                  >
                    <HugeiconsIcon icon={Delete01Icon} size={12} />
                    {t("sub.delete")}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
      </div>
    </div>
  )
}
