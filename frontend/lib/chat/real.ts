/**
 * Real ChatProvider —— 对真后端接口的实现（proactive-agent-discovery §D5 冻结契约）。
 *
 * - sendMessage: POST /agui（RunAgentInput: threadId + messages + forwardedProps.dataweave），
 *   流式读 SSE，逐块解析 AG-UI 事件（camelCase，文本增量字段 delta）→ emit。
 * - subscribeAgentStream: EventSource **直连** SSE_BASE/api/agent/stream（绕开 Next rewrite
 *   代理缓冲，已知坑），监听 agent.message / agent.finding / keepalive；断线指数退避重连。
 * - findings / approvals / sessions: 走 authFetch（相对路径经 rewrite 代理，REST 无缓冲问题）。
 *
 * 后端 🅰 相关端点未就绪时由 mode.ts 切到 mock；合流后 NEXT_PUBLIC_CHAT_MOCK=0 启用本实现。
 */
"use client"

import {
  API_BASE,
  SSE_BASE,
  authFetch,
  safeJsonParse,
  type ApiResponse,
} from "@/lib/types"
import { acceptLanguageHeader } from "@/lib/locale-client"
import { handleUnauthorized } from "@/lib/auth-401"
import type {
  AgentSession,
  AgentStreamHandlers,
  AguiEvent,
  ApplyOutcome,
  ApplyResult,
  ChatMessage,
  Finding,
  FindingAction,
  MessagePart,
} from "./types"
import type { ChatProvider, SendMessageArgs } from "./provider"

const AGENT_URL = process.env.NEXT_PUBLIC_AGENT_URL ?? "http://localhost:8000/agui"
const TOKEN_KEY = "dw.auth.token"

const nid = () =>
  typeof crypto !== "undefined" && crypto.randomUUID
    ? crypto.randomUUID()
    : `m-${Date.now()}-${Math.random().toString(36).slice(2)}`

function parseJson(s: string | null | undefined): Record<string, unknown> | null {
  if (!s) return null
  try {
    return JSON.parse(s) as Record<string, unknown>
  } catch {
    return null
  }
}

// ─── Finding 归一化（后端 evidenceJson/actionsJson JSON 串 → 已解析）────────────

interface RawFinding {
  id: number | string
  source?: string
  severity?: string
  targetType?: string
  targetId?: string | number
  title?: string
  rootCause?: string
  evidenceJson?: string | null
  actionsJson?: string | null
  status?: string
  announced?: boolean
  createdAt?: string
  updatedAt?: string
}

// 后端 GateResult.Outcome 为全大写（EXECUTED/PENDING_APPROVAL/REJECTED）；
// 前端 canonical 为 executed/PENDING_APPROVAL/rejected（与 mock/store/findings-rail 一致）。
function normOutcome(o: string | undefined): ApplyOutcome {
  const u = (o ?? "").toUpperCase()
  if (u === "EXECUTED") return "executed"
  if (u === "REJECTED") return "rejected"
  return "PENDING_APPROVAL"
}

// 后端 GET /sessions/{id}/history 返回 AgentChatMessage{id,role,partsJson(串)}；
// 前端 ChatMessage{id,role,parts(数组)} —— partsJson 解析回 parts 重水合。
function rehydrateMessage(m: {
  id?: number | string
  role?: string
  parts?: MessagePart[]
  partsJson?: string | null
}): ChatMessage {
  const parts = Array.isArray(m.parts)
    ? m.parts
    : (safeJsonParse<MessagePart[]>(m.partsJson) ?? [])
  return {
    id: m.id == null ? nid() : String(m.id),
    role: m.role === "user" ? "user" : "assistant",
    parts,
  }
}

function normalizeFinding(r: RawFinding): Finding {
  return {
    id: r.id,
    source: r.source ?? "TASK_FAILURE",
    severity: (r.severity as Finding["severity"]) ?? "WARN",
    targetType: r.targetType ?? "",
    targetId: r.targetId == null ? "" : String(r.targetId),
    title: r.title ?? "",
    rootCause: r.rootCause ?? "",
    evidence: safeJsonParse<Record<string, unknown>>(r.evidenceJson) ?? {},
    actions: safeJsonParse<FindingAction[]>(r.actionsJson) ?? [],
    status: (r.status as Finding["status"]) ?? "OPEN",
    announced: !!r.announced,
    createdAt: r.createdAt ?? "",
    updatedAt: r.updatedAt ?? "",
  }
}

// ─── AG-UI SSE 流解析 ──────────────────────────────────────

function parseSseChunk(chunk: string): AguiEvent | null {
  const dataLines: string[] = []
  for (const line of chunk.split("\n")) {
    if (line.startsWith("data:")) dataLines.push(line.slice(5).replace(/^ /, ""))
  }
  if (dataLines.length === 0) return null
  const obj = parseJson(dataLines.join("\n"))
  if (!obj || typeof obj.type !== "string") return null
  switch (obj.type) {
    case "RUN_STARTED":
      return {
        type: "RUN_STARTED",
        runId: obj.runId as string | undefined,
        threadId: obj.threadId as string | undefined,
      }
    case "TEXT_MESSAGE_START":
      return { type: "TEXT_MESSAGE_START", messageId: String(obj.messageId) }
    case "TEXT_MESSAGE_CONTENT":
      return {
        type: "TEXT_MESSAGE_CONTENT",
        messageId: String(obj.messageId),
        delta: (obj.delta as string) ?? (obj.content as string) ?? "",
      }
    case "TEXT_MESSAGE_END":
      return { type: "TEXT_MESSAGE_END", messageId: String(obj.messageId) }
    case "CUSTOM":
      return { type: "CUSTOM", name: String(obj.name), value: obj.value }
    case "RUN_FINISHED":
      return { type: "RUN_FINISHED", runId: obj.runId as string | undefined }
    default:
      return null
  }
}

async function streamAgui(
  args: SendMessageArgs,
  emit: (e: AguiEvent) => void,
): Promise<void> {
  const body = JSON.stringify({
    threadId: args.sessionId,
    messages: [{ id: nid(), role: "user", content: args.text }],
    forwardedProps: args.context ? { dataweave: args.context } : undefined,
  })
  const token =
    typeof window !== "undefined" ? localStorage.getItem(TOKEN_KEY) : null
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    Accept: "text/event-stream",
    "Accept-Language": acceptLanguageHeader(),
  }
  if (token) headers["Authorization"] = `Bearer ${token}`

  let res: Response
  try {
    res = await fetch(AGENT_URL, {
      method: "POST",
      headers,
      body,
      signal: args.signal,
    })
  } catch {
    emit({ type: "ERROR", code: "NETWORK", message: "Cannot reach Agent service" })
    return
  }
  if (!res.ok || !res.body) {
    emit({
      type: "ERROR",
      code: `HTTP_${res.status}`,
      message: `Request failed (${res.status})`,
    })
    return
  }
  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buf = ""
  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buf += decoder.decode(value, { stream: true })
      let idx: number
      while ((idx = buf.indexOf("\n\n")) >= 0) {
        const chunk = buf.slice(0, idx)
        buf = buf.slice(idx + 2)
        const ev = parseSseChunk(chunk)
        if (ev) emit(ev)
      }
    }
  } catch {
    // 中断 / 网络错误：静默，上层按是否收到 RUN_FINISHED 自行收尾。
  }
}

// ─── provider ─────────────────────────────────────────────

export const realChatProvider: ChatProvider = {
  sendMessage(args, emit) {
    return streamAgui(args, emit)
  },

  subscribeAgentStream(handlers: AgentStreamHandlers): () => void {
    let es: EventSource | null = null
    let retry = 0
    let timer: ReturnType<typeof setTimeout> | null = null
    let closed = false

    const connect = () => {
      if (closed) return
      const token =
        typeof window !== "undefined" ? localStorage.getItem(TOKEN_KEY) : null
      // SSE 必须直连后端 SSE_BASE（绕开 Next rewrite 代理缓冲，见 SSE_BASE 注释）。
      // EventSource 不支持自定义 header，token 走 query param（与 use-event-source 一致）。
      let url = `${SSE_BASE}/api/agent/stream`
      if (token) url += `?token=${encodeURIComponent(token)}`
      es = new EventSource(url)

      es.onopen = () => {
        retry = 0
        handlers.onConnectionChange?.(true)
      }
      es.addEventListener("agent.message", (ev) => {
        const data = parseJson((ev as MessageEvent).data)
        if (!data) return
        // 后端 AgentNotifier.message 文本字段为 markdown；前端 canonical 用 content。
        handlers.onMessage({
          sessionId: data.sessionId as string | undefined,
          findingId: data.findingId as number | string | undefined,
          title: data.title as string | undefined,
          content: (data.markdown as string) ?? (data.content as string) ?? "",
        })
      })
      es.addEventListener("agent.finding", (ev) => {
        // 后端 AgentNotifier.finding 推**扁平** finding（与 GET /api/findings 同形），直接归一化。
        const raw = parseJson((ev as MessageEvent).data) as RawFinding | null
        if (raw && raw.id != null) handlers.onFinding({ finding: normalizeFinding(raw) })
      })
      es.addEventListener("keepalive", () => handlers.onKeepalive?.())
      es.onerror = () => {
        handlers.onConnectionChange?.(false)
        es?.close()
        es = null
        if (closed) return
        // 指数退避重连（上限 30s）
        const backoff = Math.min(1000 * 2 ** retry, 30000)
        retry += 1
        timer = setTimeout(connect, backoff)
      }
    }
    connect()

    return () => {
      closed = true
      if (timer) clearTimeout(timer)
      es?.close()
      es = null
      handlers.onConnectionChange?.(false)
    }
  },

  async listFindings(): Promise<Finding[]> {
    const res = await authFetch(`${API_BASE}/api/findings`, { cache: "no-store" })
    if (res.status === 401) {
      handleUnauthorized()
      return []
    }
    const json = (await res.json()) as ApiResponse<RawFinding[]>
    if (json.code !== 0 || !json.data) return []
    return json.data.map(normalizeFinding)
  },

  async applyFinding(id, actionKey): Promise<ApplyResult> {
    const res = await authFetch(`${API_BASE}/api/findings/${id}/apply`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ actionKey }),
    })
    if (res.status === 401) {
      handleUnauthorized()
      throw new Error("401 Unauthorized")
    }
    const json = (await res.json()) as ApiResponse<ApplyResult>
    if (json.code !== 0 || !json.data) {
      throw new Error(json.message ?? "apply failed")
    }
    // 后端 outcome 全大写 → 归一到前端 canonical；approvalId 字段同名透传。
    return { ...json.data, outcome: normOutcome(json.data.outcome) }
  },

  async decideApproval(requestId, action, confirmation) {
    const res = await authFetch(
      `${API_BASE}/api/approvals/${requestId}/${action}`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          approver: "ui-user",
          confirmation: confirmation ?? "",
        }),
      },
    )
    const json = (await res.json()) as ApiResponse<{
      success: boolean
      message?: string
    }>
    if (json.code !== 0) return { success: false, message: json.message }
    return { success: !!json.data?.success, message: json.data?.message }
  },

  async listSessions(): Promise<AgentSession[]> {
    const res = await authFetch(`${API_BASE}/api/agent/sessions`, {
      cache: "no-store",
    })
    if (res.status === 401) {
      handleUnauthorized()
      return []
    }
    const json = (await res.json()) as ApiResponse<AgentSession[]>
    return json.code === 0 && json.data ? json.data : []
  },

  async createSession(title): Promise<AgentSession> {
    const res = await authFetch(`${API_BASE}/api/agent/sessions`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(title ? { title } : {}),
    })
    const json = (await res.json()) as ApiResponse<AgentSession>
    if (json.code !== 0 || !json.data) {
      throw new Error(json.message ?? "create session failed")
    }
    return json.data
  },

  async deleteSession(id): Promise<void> {
    await authFetch(`${API_BASE}/api/agent/sessions/${id}`, {
      method: "DELETE",
    })
  },

  async getSessionHistory(id): Promise<ChatMessage[]> {
    const res = await authFetch(
      `${API_BASE}/api/agent/sessions/${id}/history`,
      { cache: "no-store" },
    )
    if (res.status === 401) {
      handleUnauthorized()
      return []
    }
    const json = (await res.json()) as ApiResponse<
      unknown[] | { messages?: unknown[] }
    >
    if (json.code !== 0 || !json.data) return []
    const data = json.data
    const list = Array.isArray(data) ? data : (data.messages ?? [])
    return list.map((m) => rehydrateMessage(m as Parameters<typeof rehydrateMessage>[0]))
  },
}
