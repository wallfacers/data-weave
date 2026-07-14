"use client"

/**
 * 069 监督席 REST 客户端（复用既有 authFetch + X-Project-Id 注入；统一 code===0 解包）。
 * 写操作错误消息由后端本地化（GlobalExceptionHandler），前端 toast 直接透出 message（不硬编码兜底）。
 */
import { authFetch, type ApiResponse } from "@/lib/types"
import type {
  BriefingView,
  Incident,
  IncidentDetail,
  IncidentMessage,
} from "./types"

async function unwrap<T>(res: Response): Promise<T> {
  const json = (await res.json()) as ApiResponse<T>
  if (json.code !== 0) throw new Error(json.message || "请求失败")
  return json.data as T
}

interface PageResult<T> {
  items: T[]
  total: number
  page: number
  size: number
}

export async function listIncidents(params?: {
  state?: string[]
  taskDefId?: number
  page?: number
  size?: number
}): Promise<PageResult<Incident>> {
  const q = new URLSearchParams()
  params?.state?.forEach((s) => q.append("state", s))
  if (params?.taskDefId != null) q.set("taskDefId", String(params.taskDefId))
  q.set("page", String(params?.page ?? 1))
  q.set("size", String(params?.size ?? 50))
  return unwrap<PageResult<Incident>>(await authFetch(`/api/incidents?${q.toString()}`))
}

export async function getIncidentDetail(id: string): Promise<IncidentDetail> {
  return unwrap<IncidentDetail>(await authFetch(`/api/incidents/${id}`))
}

export async function getMessages(id: string, afterSeq = 0, limit = 200): Promise<IncidentMessage[]> {
  return unwrap<IncidentMessage[]>(
    await authFetch(`/api/incidents/${id}/messages?afterSeq=${afterSeq}&limit=${limit}`),
  )
}

export async function getBriefing(): Promise<BriefingView> {
  return unwrap<BriefingView>(await authFetch(`/api/incidents/briefing`))
}

// 070：发言者身份由服务端依登录凭证认定，前端不再传 actor（传了也被后端忽略）。

export async function sendChat(id: string, text: string): Promise<IncidentMessage> {
  return unwrap<IncidentMessage>(
    await authFetch(`/api/incidents/${id}/chat`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ text }),
    }),
  )
}

/** 070：打断当前事故的 Agent 输出轮次；返回是否确有在途轮次被打断。 */
export async function cancelAgent(id: string): Promise<boolean> {
  const r = await unwrap<{ cancelled: boolean }>(
    await authFetch(`/api/incidents/${id}/agent/cancel`, { method: "POST" }),
  )
  return r.cancelled
}

export async function approveProposal(
  id: string,
  proposalId: string,
  confirmation: string,
): Promise<unknown> {
  return unwrap<unknown>(
    await authFetch(`/api/incidents/${id}/proposals/${proposalId}/approve`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ confirmation }),
    }),
  )
}

export async function rejectProposal(id: string, proposalId: string): Promise<unknown> {
  return unwrap<unknown>(
    await authFetch(`/api/incidents/${id}/proposals/${proposalId}/reject`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({}),
    }),
  )
}

export async function markHandled(id: string, note?: string): Promise<void> {
  await unwrap<void>(
    await authFetch(`/api/incidents/${id}/mark-handled`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ note }),
    }),
  )
}

export async function reverify(id: string): Promise<void> {
  await unwrap<void>(
    await authFetch(`/api/incidents/${id}/reverify`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({}),
    }),
  )
}

export async function closeIncident(id: string, reason: string): Promise<void> {
  await unwrap<void>(
    await authFetch(`/api/incidents/${id}/close`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ reason }),
    }),
  )
}
