/**
 * 虚拟管家 REST API 客户端。
 * 全部接口走 authFetch + X-Project-Id，响应统一 {code, message, data}。
 */
import { authFetch, type ApiResponse } from "@/lib/types"
import type {
  ReportView,
  MessageView,
  PatrolRoutine,
  PatrolRun,
} from "./types"

const BASE = "/api/companion"

async function unwrap<T>(res: Response): Promise<T> {
  const json = (await res.json()) as ApiResponse<T>
  if (json.code !== 0) throw new Error(json.message || "请求失败")
  return json.data as T
}

/* ── 汇报 ── */

export async function fetchReports(params?: {
  status?: string
  limit?: number
}): Promise<ReportView[]> {
  const sp = new URLSearchParams()
  if (params?.status) sp.set("status", params.status)
  if (params?.limit) sp.set("limit", String(params.limit))
  const qs = sp.toString()
  return unwrap<ReportView[]>(
    await authFetch(`${BASE}/reports${qs ? `?${qs}` : ""}`)
  )
}

export async function closeReport(id: string): Promise<void> {
  await unwrap(
    await authFetch(`${BASE}/reports/${encodeURIComponent(id)}/close`, {
      method: "POST",
    })
  )
}

export async function readReport(id: string): Promise<void> {
  await unwrap(
    await authFetch(`${BASE}/reports/${encodeURIComponent(id)}/read`, {
      method: "POST",
    })
  )
}

/* ── 对话 ── */

export async function sendChat(params: {
  content: string
  reportId?: string
}): Promise<void> {
  await unwrap(
    await authFetch(`${BASE}/chat`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(params),
    })
  )
}

export async function cancelChat(): Promise<void> {
  await unwrap(
    await authFetch(`${BASE}/chat/cancel`, { method: "POST" })
  )
}

export async function fetchMessages(params?: {
  reportId?: string
  before?: string
  limit?: number
}): Promise<MessageView[]> {
  const sp = new URLSearchParams()
  if (params?.reportId) sp.set("reportId", params.reportId)
  if (params?.before) sp.set("before", params.before)
  if (params?.limit) sp.set("limit", String(params.limit))
  const qs = sp.toString()
  return unwrap<MessageView[]>(
    await authFetch(`${BASE}/messages${qs ? `?${qs}` : ""}`)
  )
}

/* ── 巡检治理 ── */

export async function fetchRoutines(): Promise<PatrolRoutine[]> {
  return unwrap<PatrolRoutine[]>(await authFetch(`${BASE}/routines`))
}

export async function patchRoutine(
  id: string,
  patch: { enabled?: boolean; cronExpression?: string; scopeJson?: unknown | null }
): Promise<void> {
  await unwrap(
    await authFetch(`${BASE}/routines/${encodeURIComponent(id)}`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(patch),
    })
  )
}

export async function triggerRoutine(id: string): Promise<{ runId: string }> {
  return unwrap<{ runId: string }>(
    await authFetch(`${BASE}/routines/${encodeURIComponent(id)}/trigger`, {
      method: "POST",
    })
  )
}

export async function fetchRuns(
  routineId: string,
  limit?: number
): Promise<PatrolRun[]> {
  const sp = new URLSearchParams()
  if (limit) sp.set("limit", String(limit))
  const qs = sp.toString()
  return unwrap<PatrolRun[]>(
    await authFetch(
      `${BASE}/routines/${encodeURIComponent(routineId)}/runs${qs ? `?${qs}` : ""}`
    )
  )
}
