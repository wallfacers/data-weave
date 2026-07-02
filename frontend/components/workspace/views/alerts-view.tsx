"use client"

import { useState, useEffect, useCallback } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import { LoadingState } from "@/components/workspace/shared/loading-state"
import {
  Alert02Icon,
  BellIcon,
  Delete01Icon,
  HistoryIcon,
  Mail01Icon,
  MuteIcon,
  PlusSignIcon,
  Settings02Icon,
  Tick01Icon,
} from "@hugeicons/core-free-icons"
import { useLiveData } from "@/lib/workspace/use-api"
import type { ViewProps } from "@/lib/workspace/registry"
import { ViewRefreshControl } from "./view-refresh-control"

const API_BASE = process.env.NEXT_PUBLIC_API_BASE || "http://localhost:8000"

interface AlertRule {
  id: number; name: string; signalSource: string; severity: string;
  enabled: number; evalMode: string; state?: string;
}

interface AlertEvent {
  id: number; ruleId: number; state: string; severity: string;
  fingerprint: string; count: number; firstFiredAt: string; lastFiredAt: string;
  resolvedAt?: string;
}

interface AlertChannel {
  id: number; name: string; type: string; enabled: number;
}

interface AlertSilence {
  id: number; matchJson: string; startsAt: string; endsAt: string; reason: string;
}

type TabKey = "rules" | "active" | "history" | "channels" | "silences"

interface AlertsBundle {
  rules: AlertRule[]
  events: AlertEvent[]
  channels: AlertChannel[]
  silences: AlertSilence[]
}

export function AlertsView({ active }: ViewProps) {
  const t = useTranslations("alerts")
  const [tab, setTab] = useState<TabKey>("active")

  const fetchJson = useCallback(async (path: string) => {
    const res = await fetch(`${API_BASE}${path}`, {
      headers: { "Authorization": `Bearer ${localStorage.getItem("dw.auth.token") || ""}` }
    })
    const json = await res.json()
    return json.data
  }, [])

  // 4 端点聚合 fetcher（I2）：任一端点失败 → reject → 保留旧数据置 stale
  const bundleFetcher = useCallback(async (): Promise<AlertsBundle> => {
    const [r, e, c, s] = await Promise.all([
      fetchJson("/api/alert/rules?limit=100"),
      fetchJson("/api/alert/events?state=" + (tab === "active" ? "FIRING" : "") + "&limit=100"),
      fetchJson("/api/alert/channels"),
      fetchJson("/api/alert/silences"),
    ])
    return {
      rules: r?.items || r || [],
      events: e?.items || e || [],
      channels: c || [],
      silences: s || [],
    }
  }, [tab, fetchJson])

  const [autoEnabled, setAutoEnabled] = useState(true)

  const {
    data: bundle,
    loading,
    refreshing,
    stale,
    lastUpdatedAt,
    refresh,
  } = useLiveData<AlertsBundle>(bundleFetcher, { active, enabled: autoEnabled, deps: [tab] })

  const rules = bundle?.rules ?? []
  const events = bundle?.events ?? []
  const channels = bundle?.channels ?? []
  const silences = bundle?.silences ?? []

  const handleAck = async (eventId: number) => {
    await fetch(`${API_BASE}/api/alert/events/${eventId}/ack`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${localStorage.getItem("dw.auth.token") || ""}`
      },
      body: JSON.stringify({ ackedBy: 1 })
    })
    refresh()
  }

  const tabs: { key: TabKey; labelKey: string; icon: typeof Alert02Icon }[] = [
    { key: "active", labelKey: "activeAlerts", icon: Alert02Icon },
    { key: "history", labelKey: "history", icon: HistoryIcon },
    { key: "rules", labelKey: "rules", icon: Settings02Icon },
    { key: "channels", labelKey: "channels", icon: Mail01Icon },
    { key: "silences", labelKey: "silences", icon: MuteIcon },
  ]

  const severityColor = (s: string) =>
    s === "CRITICAL" ? "text-red-600 bg-red-50" :
    s === "WARNING" ? "text-amber-600 bg-amber-50" :
    "text-blue-600 bg-blue-50"

  const stateColor = (s: string) =>
    s === "FIRING" ? "text-red-700 bg-red-100" :
    s === "RESOLVED" ? "text-green-700 bg-green-100" :
    s === "ACKED" ? "text-gray-600 bg-gray-100" :
    "text-purple-700 bg-purple-100"

  return (
    <div className="flex flex-col h-full">
      {/* Tab bar — 下划线式，与 settings-view / event-center-view 同款 */}
      <div className="flex items-center gap-1 border-b h-11 px-5" role="tablist">
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

      {/* Toolbar */}
      <div className="flex items-center justify-end px-5 py-2">
        <ViewRefreshControl
          lastUpdatedAt={lastUpdatedAt}
          refreshing={refreshing}
          stale={stale}
          autoEnabled={autoEnabled}
          onToggleAuto={setAutoEnabled}
          onRefresh={refresh}
        />
      </div>

      {/* Content */}
      <div className="flex min-h-0 flex-1 flex-col overflow-auto px-5 pb-5">
        {loading && bundle == null && <LoadingState active={loading} />}

        {/* Active Alerts & History */}
        {(tab === "active" || tab === "history") && (
          <div className="flex flex-1 flex-col space-y-2">
            {events.length === 0 && !loading && (
              <div className="flex flex-1 flex-col items-center justify-center gap-2 text-muted-foreground">
                <HugeiconsIcon icon={Alert02Icon} size={32} />
                <p className="text-sm">{t("noData")}</p>
              </div>
            )}
            {events.map(e => (
              <div key={e.id} className="border rounded-lg p-3 flex items-center justify-between gap-4">
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2">
                    <span className={`px-1.5 py-0.5 rounded text-xs font-medium ${severityColor(e.severity)}`}>
                      {e.severity}
                    </span>
                    <span className={`px-1.5 py-0.5 rounded text-xs font-medium ${stateColor(e.state)}`}>
                      {e.state === "FIRING" ? t("stateFiring") :
                       e.state === "RESOLVED" ? t("stateResolved") :
                       e.state === "ACKED" ? t("stateAcked") : t("stateSuppressed")}
                    </span>
                    <span className="text-xs text-muted-foreground">{t("count")}: {e.count}</span>
                  </div>
                  <p className="text-xs text-muted-foreground mt-1 truncate font-mono">{e.fingerprint}</p>
                  <p className="text-xs text-muted-foreground">
                    {e.firstFiredAt ? new Date(e.firstFiredAt).toLocaleString() : "-"}
                    {e.resolvedAt && ` → ${new Date(e.resolvedAt).toLocaleString()}`}
                  </p>
                </div>
                {e.state === "FIRING" && (
                  <button
                    onClick={() => handleAck(e.id)}
                    className="flex items-center gap-1 px-2 py-1 text-xs rounded bg-primary text-primary-foreground hover:opacity-80"
                  >
                    <HugeiconsIcon icon={Tick01Icon} size={12} />
                    {t("ack")}
                  </button>
                )}
              </div>
            ))}
          </div>
        )}

        {/* Rules */}
        {tab === "rules" && (
          <div className="flex flex-1 flex-col space-y-2">
            {rules.length === 0 && !loading && (
              <div className="flex flex-1 flex-col items-center justify-center gap-2 text-muted-foreground">
                <HugeiconsIcon icon={Settings02Icon} size={32} />
                <p className="text-sm">{t("noData")}</p>
              </div>
            )}
            {rules.map(r => (
              <div key={r.id} className="border rounded-lg p-3 flex items-center justify-between gap-4">
                <div className="flex-1 min-w-0">
                  <p className="font-medium text-sm">{r.name}</p>
                  <p className="text-xs text-muted-foreground">
                    {r.signalSource} · {r.evalMode} · <span className={severityColor(r.severity)}>{r.severity}</span>
                    {r.enabled === 0 && " · Disabled"}
                  </p>
                </div>
              </div>
            ))}
          </div>
        )}

        {/* Channels */}
        {tab === "channels" && (
          <div className="flex flex-1 flex-col space-y-2">
            {channels.length === 0 && !loading && (
              <div className="flex flex-1 flex-col items-center justify-center gap-2 text-muted-foreground">
                <HugeiconsIcon icon={Mail01Icon} size={32} />
                <p className="text-sm">{t("noData")}</p>
              </div>
            )}
            {channels.map(c => (
              <div key={c.id} className="border rounded-lg p-3 flex items-center justify-between gap-4">
                <div className="flex-1">
                  <p className="font-medium text-sm">{c.name}</p>
                  <p className="text-xs text-muted-foreground">{c.type}{c.enabled === 0 && " · Disabled"}</p>
                </div>
              </div>
            ))}
          </div>
        )}

        {/* Silences */}
        {tab === "silences" && (
          <div className="flex flex-1 flex-col space-y-2">
            {silences.length === 0 && !loading && (
              <div className="flex flex-1 flex-col items-center justify-center gap-2 text-muted-foreground">
                <HugeiconsIcon icon={MuteIcon} size={32} />
                <p className="text-sm">{t("noData")}</p>
              </div>
            )}
            {silences.map(s => (
              <div key={s.id} className="border rounded-lg p-3">
                <p className="text-sm">{s.reason || t("silences")}</p>
                <p className="text-xs text-muted-foreground">
                  {new Date(s.startsAt).toLocaleString()} → {new Date(s.endsAt).toLocaleString()}
                </p>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
