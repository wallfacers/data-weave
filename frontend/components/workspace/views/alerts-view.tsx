"use client"

import { useState, useEffect, useCallback } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  BellIcon,
  PlusSignIcon,
  Tick01Icon,
  Delete01Icon,
  Alert02Icon,
} from "@hugeicons/core-free-icons"

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

export function AlertsView() {
  const t = useTranslations("alerts")
  const [tab, setTab] = useState<TabKey>("active")
  const [rules, setRules] = useState<AlertRule[]>([])
  const [events, setEvents] = useState<AlertEvent[]>([])
  const [channels, setChannels] = useState<AlertChannel[]>([])
  const [silences, setSilences] = useState<AlertSilence[]>([])
  const [loading, setLoading] = useState(false)

  const fetchJson = useCallback(async (path: string) => {
    const res = await fetch(`${API_BASE}${path}`, {
      headers: { "Authorization": `Bearer ${localStorage.getItem("dw.auth.token") || ""}` }
    })
    const json = await res.json()
    return json.data
  }, [])

  const loadData = useCallback(async () => {
    setLoading(true)
    try {
      const [r, e, c, s] = await Promise.all([
        fetchJson("/api/alert/rules?limit=100"),
        fetchJson("/api/alert/events?state=" + (tab === "active" ? "FIRING" : "") + "&limit=100"),
        fetchJson("/api/alert/channels"),
        fetchJson("/api/alert/silences"),
      ])
      setRules(r?.items || r || [])
      setEvents(e?.items || e || [])
      setChannels(c || [])
      setSilences(s || [])
    } catch (err) { console.error("Failed to load alerts data", err) }
    setLoading(false)
  }, [tab, fetchJson])

  useEffect(() => { loadData() }, [loadData])

  const handleAck = async (eventId: number) => {
    await fetch(`${API_BASE}/api/alert/events/${eventId}/ack`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${localStorage.getItem("dw.auth.token") || ""}`
      },
      body: JSON.stringify({ ackedBy: 1 })
    })
    loadData()
  }

  const tabs: { key: TabKey; label: string }[] = [
    { key: "active", label: t("activeAlerts") },
    { key: "history", label: t("history") },
    { key: "rules", label: t("rules") },
    { key: "channels", label: t("channels") },
    { key: "silences", label: t("silences") },
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
      {/* Tab bar */}
      <div className="flex gap-1 border-b px-4 py-2">
        {tabs.map(tb => (
          <button
            key={tb.key}
            onClick={() => setTab(tb.key)}
            className={`px-3 py-1.5 text-sm rounded-t-md transition-colors ${
              tab === tb.key
                ? "bg-background text-foreground border-b-2 border-primary font-medium"
                : "text-muted-foreground hover:text-foreground"
            }`}
          >
            {tb.label}
          </button>
        ))}
        <div className="flex-1" />
        <button className="p-1.5 text-muted-foreground hover:text-foreground" onClick={loadData} title="Refresh">
          <HugeiconsIcon icon={BellIcon} size={16} />
        </button>
      </div>

      {/* Content */}
      <div className="flex-1 overflow-auto p-4">
        {loading && <p className="text-muted-foreground text-sm">{t("loading")}</p>}

        {/* Active Alerts & History */}
        {(tab === "active" || tab === "history") && (
          <div className="space-y-2">
            {events.length === 0 && !loading && <p className="text-muted-foreground text-sm">{t("noData")}</p>}
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
          <div className="space-y-2">
            {rules.length === 0 && !loading && <p className="text-muted-foreground text-sm">{t("noData")}</p>}
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
          <div className="space-y-2">
            {channels.length === 0 && !loading && <p className="text-muted-foreground text-sm">{t("noData")}</p>}
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
          <div className="space-y-2">
            {silences.length === 0 && !loading && <p className="text-muted-foreground text-sm">{t("noData")}</p>}
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
