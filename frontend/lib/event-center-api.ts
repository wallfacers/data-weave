/**
 * 事件中心 API 客户端 —— /api/events/*（027）。
 * 统一 {code, data} 包络；Bearer 凭据复用 dw.auth.token。
 */

const API_BASE = process.env.NEXT_PUBLIC_API_BASE || "http://localhost:8000"

export type EventType =
  | "SLA_BREACH"
  | "QUALITY_FAILED"
  | "TASK_FAILED"
  | "TASK_TIMEOUT"
  | "WORKFLOW_STATE"
  | "NODE_OFFLINE"
  | "METRIC_BREACH"

export type RefKind = "TASK" | "METRIC" | "TABLE" | "WORKFLOW"

export interface HealthEvent {
  id: number
  tenantId: number
  type: string
  severity?: string
  fingerprint: string
  refKind?: RefKind
  refId?: string
  refName?: string
  summary?: string
  contextJson?: string
  count: number
  firstOccurredAt?: string
  lastOccurredAt?: string
}

export interface EventSubscription {
  id: number
  typeFilter?: string
  minSeverity?: string
  refKind?: string
  refId?: string
  channelId: number
  enabled: number
}

export interface AlertChannelLite {
  id: number
  name: string
  type: string
  enabled: number
}

export interface EventQuery {
  type?: string
  severity?: string
  refKind?: string
  refId?: string
  page?: number
  size?: number
}

function authHeaders(): Record<string, string> {
  const token = typeof window !== "undefined" ? localStorage.getItem("dw.auth.token") || "" : ""
  return { Authorization: `Bearer ${token}` }
}

async function getJson(path: string): Promise<{ code: number; data: unknown; message?: string }> {
  const res = await fetch(`${API_BASE}${path}`, { headers: authHeaders() })
  return res.json()
}

export async function fetchEvents(q: EventQuery): Promise<{ items: HealthEvent[]; total: number }> {
  const params = new URLSearchParams()
  if (q.type) params.set("type", q.type)
  if (q.severity) params.set("severity", q.severity)
  if (q.refKind) params.set("refKind", q.refKind)
  if (q.refId) params.set("refId", q.refId)
  params.set("page", String(q.page ?? 1))
  params.set("size", String(q.size ?? 20))
  const json = await getJson(`/api/events?${params.toString()}`)
  const data = (json.data ?? {}) as { items?: HealthEvent[]; total?: number }
  return { items: data.items ?? [], total: data.total ?? 0 }
}

export async function fetchSubscriptions(): Promise<EventSubscription[]> {
  const json = await getJson(`/api/events/subscriptions`)
  return (json.data as EventSubscription[]) ?? []
}

export async function fetchChannels(): Promise<AlertChannelLite[]> {
  const json = await getJson(`/api/alert/channels`)
  return (json.data as AlertChannelLite[]) ?? []
}

export async function createSubscription(body: {
  typeFilter?: string
  minSeverity?: string
  refKind?: string
  refId?: string
  channelId: number
}): Promise<{ code: number; message?: string }> {
  const res = await fetch(`${API_BASE}/api/events/subscriptions`, {
    method: "POST",
    headers: { ...authHeaders(), "Content-Type": "application/json" },
    body: JSON.stringify(body),
  })
  return res.json()
}

export async function deleteSubscription(id: number): Promise<void> {
  await fetch(`${API_BASE}/api/events/subscriptions/${id}`, {
    method: "DELETE",
    headers: authHeaders(),
  })
}

/** 关联对象 → Workspace 深链 viewType（前端路由 ?open=）。 */
export function refKindToView(refKind?: RefKind): string | null {
  switch (refKind) {
    case "TABLE":
      return "lineage"
    case "METRIC":
      return "metrics"
    case "TASK":
    case "WORKFLOW":
      return "ops"
    default:
      return null
  }
}
