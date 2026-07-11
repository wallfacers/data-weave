/**
 * Incident API 客户端——类型对齐 contracts/incident-api.md。
 */
const API_BASE = process.env.NEXT_PUBLIC_API_BASE || "http://localhost:8000"

import { projectIdHeader } from "./project-header"

function authHeaders(): Record<string, string> {
  const token = typeof window !== "undefined" ? localStorage.getItem("dw.auth.token") || "" : ""
  return { Authorization: `Bearer ${token}`, "Content-Type": "application/json", ...projectIdHeader() }
}

async function getJson(path: string): Promise<{ code: number; data: unknown; message?: string }> {
  const res = await fetch(`${API_BASE}${path}`, { headers: authHeaders() })
  if (res.status === 401) throw new Error("401 Unauthorized")
  return res.json()
}

async function postJson(path: string, body: unknown): Promise<{ code: number; data: unknown; message?: string }> {
  const res = await fetch(`${API_BASE}${path}`, {
    method: "POST",
    headers: authHeaders(),
    body: JSON.stringify(body),
  })
  if (res.status === 401) throw new Error("401 Unauthorized")
  return res.json()
}

// —— 类型（对齐 contracts/incident-api.md）——

export type IncidentState = "OPEN" | "MITIGATING" | "RESOLVED" | "SUPPRESSED" | "CLOSED"
export type IncidentSeverity = "CRITICAL" | "HIGH" | "MEDIUM" | "LOW" | "WARNING"
export type SourceKind = "TASK" | "WORKFLOW" | "NODE"

export interface IncidentCard {
  id: number
  title: string
  severity: IncidentSeverity
  state: IncidentState
  signature: string
  sourceKind: SourceKind
  sourceRefId: string
  sourceRefName: string
  workflowInstanceId: string | null
  occurrenceCount: number
  firstSeenAt: string
  lastSeenAt: string
  blastRadius: number | null
  timeBudgetAt: string | null
  suppressReason: string | null
  resolutionKind: string | null
  resolvedAt: string | null
  healByType: string | null      // 064 愈合条件——恢复信号事件类型
  healByRefId: string | null     // 064 愈合条件——恢复信号引用 ID
  pendingActionCount: number
  priorIncidentCount: number
  diagnosis: null
  proposal: null
}

export interface IncidentQueue {
  active: IncidentCard[]
  recentResolved: IncidentCard[]
  activeCount: number
  recentResolvedCount: number
}

export type TimelineKind = "SIGNAL" | "STATE_CHANGE" | "ACTION" | "APPROVAL" | "NOTE"

export interface TimelineEntry {
  seq: number
  kind: TimelineKind
  payload: Record<string, unknown>
  actor: string
  createdAt: string
}

export interface IncidentAction {
  id: number
  actionType: string
  approvalStatus: string
  summary: string
  policyLevel: string
  executedAt: string | null
  resultJson: string | null
}

export interface IncidentDetail {
  incident: IncidentCard
  timeline: TimelineEntry[]
  actions: IncidentAction[]
}

export interface HistoryResult {
  items: IncidentCard[]
  total: number
}

export type RerunOutcome = "EXECUTED" | "PENDING_APPROVAL" | "REJECTED"

export interface RerunResult {
  outcome: RerunOutcome
  actionId: number
  message: string
}

// —— API 函数 ——

export async function fetchIncidentQueue(projectId: string): Promise<IncidentQueue> {
  const json = await getJson(`/api/incidents?projectId=${encodeURIComponent(projectId)}`)
  return (json.data ?? { active: [], recentResolved: [], activeCount: 0, recentResolvedCount: 0 }) as IncidentQueue
}

export async function fetchIncidentHistory(
  projectId: string,
  params?: { state?: string; signature?: string; from?: string; to?: string; page?: number; size?: number },
): Promise<HistoryResult> {
  const sp = new URLSearchParams({ projectId })
  if (params?.state) sp.set("state", params.state)
  if (params?.signature) sp.set("signature", params.signature)
  if (params?.from) sp.set("from", params.from)
  if (params?.to) sp.set("to", params.to)
  if (params?.page !== undefined) sp.set("page", String(params.page))
  if (params?.size !== undefined) sp.set("size", String(params.size))
  const json = await getJson(`/api/incidents/history?${sp.toString()}`)
  return (json.data ?? { items: [], total: 0 }) as HistoryResult
}

export async function fetchIncidentDetail(id: number, projectId: string): Promise<IncidentDetail> {
  const json = await getJson(`/api/incidents/${id}?projectId=${encodeURIComponent(projectId)}`)
  return json.data as IncidentDetail
}

export async function rerunIncident(
  id: number,
  taskInstanceId: string,
  projectId: string,
): Promise<RerunResult> {
  const json = await postJson(
    `/api/incidents/${id}/rerun?projectId=${encodeURIComponent(projectId)}`,
    { taskInstanceId },
  )
  return (json.data ?? json) as RerunResult
}

export async function suppressIncident(
  id: number,
  reason: string,
  projectId: string,
): Promise<{ message?: string }> {
  const json = await postJson(
    `/api/incidents/${id}/suppress?projectId=${encodeURIComponent(projectId)}`,
    { reason },
  )
  return json.data as { message?: string } ?? json
}

export async function unsuppressIncident(
  id: number,
  projectId: string,
): Promise<{ message?: string }> {
  const json = await postJson(
    `/api/incidents/${id}/unsuppress?projectId=${encodeURIComponent(projectId)}`,
    {},
  )
  return json.data as { message?: string } ?? json
}

export async function addIncidentNote(
  id: number,
  text: string,
  projectId: string,
): Promise<{ message?: string }> {
  const json = await postJson(
    `/api/incidents/${id}/notes?projectId=${encodeURIComponent(projectId)}`,
    { text },
  )
  return json.data as { message?: string } ?? json
}

/** 审批内联——复用既有 /api/approvals 端点 */
export async function approveAction(actionId: number): Promise<{ message?: string }> {
  const json = await postJson(`/api/approvals/${actionId}/approve`, {})
  return json.data as { message?: string } ?? json
}

export async function rejectAction(actionId: number): Promise<{ message?: string }> {
  const json = await postJson(`/api/approvals/${actionId}/reject`, {})
  return json.data as { message?: string } ?? json
}
