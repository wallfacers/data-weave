"use client"

import { useCallback, useEffect, useState } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  Alert02Icon,
  ArrowRight01Icon,
  Delete01Icon,
  PlusSignIcon,
  RefreshIcon,
} from "@hugeicons/core-free-icons"
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

function severityClass(sev?: string): string {
  switch ((sev || "").toUpperCase()) {
    case "CRITICAL":
    case "HIGH":
      return "text-destructive"
    case "WARNING":
    case "MEDIUM":
      return "text-amber-500"
    default:
      return "text-muted-foreground"
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

  return (
    <div className="flex h-full flex-col gap-4 p-4">
      {/* tabs */}
      <div className="flex items-center gap-2 border-b border-border">
        {(["events", "subscriptions"] as TabKey[]).map((k) => (
          <button
            key={k}
            onClick={() => setTab(k)}
            className={`px-3 py-2 text-sm ${
              tab === k ? "border-b-2 border-primary text-foreground" : "text-muted-foreground"
            }`}
          >
            {t(`tab.${k}`)}
          </button>
        ))}
      </div>

      {tab === "events" ? (
        <>
          {/* filters */}
          <div className="flex flex-wrap items-center gap-2">
            <select
              value={typeFilter}
              onChange={(e) => setTypeFilter(e.target.value)}
              className="rounded-md border border-border bg-background px-2 py-1 text-sm"
            >
              <option value="">{t("filter.allTypes")}</option>
              {EVENT_TYPES.map((ty) => (
                <option key={ty} value={ty}>
                  {ty}
                </option>
              ))}
            </select>
            <select
              value={sevFilter}
              onChange={(e) => setSevFilter(e.target.value)}
              className="rounded-md border border-border bg-background px-2 py-1 text-sm"
            >
              <option value="">{t("filter.allSeverities")}</option>
              {SEVERITIES.map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>
            <button
              onClick={loadEvents}
              className="inline-flex items-center gap-1 rounded-md border border-border px-2 py-1 text-sm text-muted-foreground hover:text-foreground"
            >
              <HugeiconsIcon icon={RefreshIcon} size={14} />
              {t("refresh")}
            </button>
            <span className="ml-auto text-xs text-muted-foreground">{t("total", { count: total })}</span>
          </div>

          {/* timeline */}
          <div className="flex-1 overflow-auto">
            {loading ? (
              <p className="text-sm text-muted-foreground">{t("loading")}</p>
            ) : events.length === 0 ? (
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
                      {e.severity || "—"}
                    </span>
                    <div className="min-w-0 flex-1">
                      <div className="flex items-center gap-2">
                        <span className="rounded bg-muted px-1.5 py-0.5 text-xs text-muted-foreground">
                          {e.type}
                        </span>
                        {e.count > 1 && (
                          <span className="text-xs text-muted-foreground">×{e.count}</span>
                        )}
                      </div>
                      <p className="mt-1 truncate text-sm text-foreground">{e.summary || e.fingerprint}</p>
                      <p className="mt-0.5 text-xs text-muted-foreground">{e.lastOccurredAt}</p>
                    </div>
                    {refKindToView(e.refKind) && (
                      <button
                        onClick={() => onDeepLink(e)}
                        title={t("openRef", { kind: e.refKind || "" })}
                        className="inline-flex items-center gap-1 rounded-md px-2 py-1 text-xs text-primary hover:underline"
                      >
                        {e.refKind}
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
        <div className="flex flex-1 flex-col gap-4 overflow-auto">
          {/* create subscription */}
          <div className="flex flex-wrap items-end gap-2 rounded-lg border border-border bg-card p-3">
            <select
              value={newSub.typeFilter}
              onChange={(e) => setNewSub({ ...newSub, typeFilter: e.target.value })}
              className="rounded-md border border-border bg-background px-2 py-1 text-sm"
            >
              <option value="">{t("filter.allTypes")}</option>
              {EVENT_TYPES.map((ty) => (
                <option key={ty} value={ty}>
                  {ty}
                </option>
              ))}
            </select>
            <select
              value={newSub.minSeverity}
              onChange={(e) => setNewSub({ ...newSub, minSeverity: e.target.value })}
              className="rounded-md border border-border bg-background px-2 py-1 text-sm"
            >
              <option value="">{t("sub.anySeverity")}</option>
              {SEVERITIES.map((s) => (
                <option key={s} value={s}>
                  ≥ {s}
                </option>
              ))}
            </select>
            <select
              value={newSub.channelId}
              onChange={(e) => setNewSub({ ...newSub, channelId: e.target.value })}
              className="rounded-md border border-border bg-background px-2 py-1 text-sm"
            >
              <option value="">{t("sub.selectChannel")}</option>
              {channels.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name} ({c.type})
                </option>
              ))}
            </select>
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
            <p className="text-sm text-muted-foreground">{t("sub.empty")}</p>
          ) : (
            <ul className="flex flex-col gap-2">
              {subs.map((s) => (
                <li
                  key={s.id}
                  className="flex items-center gap-3 rounded-lg border border-border bg-card p-3 text-sm"
                >
                  <span className="rounded bg-muted px-1.5 py-0.5 text-xs">
                    {s.typeFilter || t("filter.allTypes")}
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
  )
}
