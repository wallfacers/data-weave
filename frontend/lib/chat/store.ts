/**
 * 多会话聊天 store（proactive-agent-discovery §D4/Group 7）。
 *
 * 真相源：sessions / activeId / runtimes（每会话一个 ChatRuntime）/ findings（举手台）。
 * 参照 workhorse-assistant SessionProvider 的「每活会话挂流」模型——但这里用 zustand +
 * chatProvider：用户发问走 provider.sendMessage 流式 emit AG-UI 事件 → reduceAguiEvent
 * 折叠成 parts；agent-stream 的 agent.message（主动开口）push 进目标 runtime，agent.finding
 * 刷新举手台。非可见会话的缓冲同样持续接收（D2）。
 */
"use client"

import { create } from "zustand"

import { useWorkspaceStore } from "@/lib/workspace/store"
import { chatProvider } from "./provider"
import {
  dropPending,
  emptyRuntime,
  isPendingOnly,
  type AgentMessageEvent,
  type AgentPageContext,
  type AgentSession,
  type AguiEvent,
  type ApplyResult,
  type ChatMessage,
  type ChatRuntime,
  type Finding,
  type MessagePart,
} from "./types"

// ─── 模块级 scratch / 取消控制（不入 React state：每 token 变）───────────────

interface Scratch {
  /** 当前 assistant 轮的消息 id（TEXT_MESSAGE_START 认领）。 */
  currentMessageId: string | null
  /** sendMessage 时先放的 pending 占位 id，TEXT_MESSAGE_START 到达时被认领。 */
  localPendingId: string | null
}

const scratches = new Map<string, Scratch>()
const scratchFor = (sid: string): Scratch => {
  let s = scratches.get(sid)
  if (!s) {
    s = { currentMessageId: null, localPendingId: null }
    scratches.set(sid, s)
  }
  return s
}
const scratchDelete = (sid: string) => scratches.delete(sid)

const aborts = new Map<string, AbortController>()
let resultSeq = 0

const uid = (p: string) =>
  `${p}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`

// ─── AG-UI 事件 → parts reducer（纯函数）─────────────────────────────────────

function appendPart(
  messages: ChatMessage[],
  scratch: Scratch,
  part: MessagePart,
): ChatMessage[] {
  const id = scratch.currentMessageId
  if (id && messages.some((m) => m.id === id && m.role === "assistant")) {
    return messages.map((m) =>
      m.id === id ? { ...m, parts: [...dropPending(m.parts), part] } : m,
    )
  }
  const newId = uid("a")
  scratch.currentMessageId = newId
  return [...messages, { id: newId, role: "assistant", parts: [part] }]
}

function applyCustom(
  messages: ChatMessage[],
  name: string,
  value: unknown,
  scratch: Scratch,
): ChatMessage[] {
  if (name === "dataweave.approval" && value && typeof value === "object") {
    const a = value as {
      approvalId: number | string
      level?: string
      summary?: string
      message?: string
      reason?: string
      resource?: string
      tool?: string
      dangerous?: boolean
    }
    const part: MessagePart = {
      type: "permission",
      requestId: String(a.approvalId),
      level: a.level,
      summary: a.summary,
      reason: a.message ?? a.reason,
      resource: a.resource,
      tool: a.tool,
      dangerous: a.dangerous,
      status: "pending",
    }
    return appendPart(messages, scratch, part)
  }
  if (name === "dataweave.ui.open") return messages // side-effect 在 emit 回调处理，不进消息
  if (value && typeof value === "object") {
    const v = value as {
      columns?: unknown
      rows?: unknown
      kind?: string
      title?: string
      sql?: string
    }
    if (Array.isArray(v.columns) && Array.isArray(v.rows)) {
      const part: MessagePart = {
        type: "result",
        data: {
          id: ++resultSeq,
          kind: v.kind,
          title: v.title,
          sql: v.sql,
          columns: v.columns as string[],
          rows: v.rows as Record<string, unknown>[],
        },
      }
      return appendPart(messages, scratch, part)
    }
  }
  return messages
}

function reduceAguiEvent(
  rt: ChatRuntime,
  e: AguiEvent,
  scratch: Scratch,
): ChatRuntime {
  let messages = rt.messages
  const streaming = new Set(rt.streaming)
  switch (e.type) {
    case "RUN_STARTED":
      break
    case "TEXT_MESSAGE_START": {
      scratch.currentMessageId = e.messageId
      streaming.add(e.messageId)
      const lp = scratch.localPendingId
      if (lp && messages.some((m) => m.id === lp)) {
        // 认领 sendMessage 预放的 pending 占位：重命名 id（后续 CONTENT 用 messageId 匹配）
        messages = messages.map((m) => (m.id === lp ? { ...m, id: e.messageId } : m))
        scratch.localPendingId = null
      } else if (!messages.some((m) => m.id === e.messageId)) {
        messages = [
          ...messages,
          { id: e.messageId, role: "assistant", parts: [{ type: "pending" }] },
        ]
      }
      break
    }
    case "TEXT_MESSAGE_CONTENT": {
      messages = messages.map((m) => {
        if (m.id !== e.messageId) return m
        const hasText = m.parts.some((p) => p.type === "text")
        const parts: MessagePart[] = hasText
          ? m.parts.map((p) =>
              p.type === "text" ? { ...p, content: p.content + e.delta } : p,
            )
          : [...dropPending(m.parts), { type: "text", content: e.delta }]
        return { ...m, parts }
      })
      break
    }
    case "TEXT_MESSAGE_END": {
      streaming.delete(e.messageId)
      messages = messages.map((m) =>
        m.id === e.messageId ? { ...m, parts: dropPending(m.parts) } : m,
      )
      break
    }
    case "CUSTOM": {
      messages = applyCustom(messages, e.name, e.value, scratch)
      break
    }
    case "ERROR": {
      streaming.clear()
      scratch.currentMessageId = null
      scratch.localPendingId = null
      messages = [
        ...messages.filter((m) => !isPendingOnly(m)),
        {
          id: uid("e"),
          role: "assistant",
          parts: [{ type: "error", code: e.code, message: e.message }],
        },
      ]
      break
    }
    case "RUN_FINISHED": {
      scratch.currentMessageId = null
      scratch.localPendingId = null
      streaming.clear()
      messages = messages.filter((m) => !isPendingOnly(m))
      break
    }
  }
  return { messages, streaming }
}

type PermissionPart = Extract<MessagePart, { type: "permission" }>

/** 更新指定 permission part（按 requestId 定位），返回新消息数组。 */
function updatePermissionPart(
  messages: ChatMessage[],
  requestId: string,
  patch: (p: PermissionPart) => PermissionPart,
): ChatMessage[] {
  return messages.map((m) => ({
    ...m,
    parts: m.parts.map((p): MessagePart =>
      p.type === "permission" && p.requestId === requestId ? patch(p) : p,
    ),
  }))
}

// ─── store ────────────────────────────────────────────────

interface ChatState {
  sessions: AgentSession[]
  activeId: string | null
  runtimes: Record<string, ChatRuntime>
  loaded: boolean
  findings: Finding[]
  findingsLoading: boolean
  streamConnected: boolean

  init: () => Promise<void>
  newSession: () => Promise<void>
  switchSession: (id: string) => Promise<void>
  deleteSession: (id: string) => Promise<void>
  sendMessage: (text: string, context?: AgentPageContext) => Promise<void>
  cancel: () => void
  decidePermission: (
    requestId: string,
    action: "approve" | "reject",
  ) => Promise<void>
  refreshFindings: () => Promise<void>
  applyFinding: (id: number | string, actionKey: string) => Promise<ApplyResult>
}

let unsubscribeStream: (() => void) | null = null

export const useChatStore = create<ChatState>()((set, get) => ({
  sessions: [],
  activeId: null,
  runtimes: {},
  loaded: false,
  findings: [],
  findingsLoading: false,
  streamConnected: false,

  init: async () => {
    if (get().loaded) return
    let sessions = await chatProvider.listSessions()
    if (sessions.length === 0) {
      const s = await chatProvider.createSession()
      sessions = [s]
    }
    const conv =
      typeof window !== "undefined"
        ? localStorage.getItem("dw.conversationId")
        : null
    const active =
      sessions.find((s) => s.id === conv) ?? sessions[sessions.length - 1]!
    const hist = await chatProvider.getSessionHistory(active.id)
    set({
      sessions,
      activeId: active.id,
      runtimes: { [active.id]: { messages: hist, streaming: new Set() } },
      loaded: true,
    })
    void get().refreshFindings()
    if (unsubscribeStream) unsubscribeStream()
    unsubscribeStream = chatProvider.subscribeAgentStream({
      onMessage: (e: AgentMessageEvent) => {
        const sid = e.sessionId ?? get().activeId
        if (!sid) return
        set((state) => {
          const rt = state.runtimes[sid] ?? emptyRuntime()
          const msg: ChatMessage = {
            id: uid("am"),
            role: "assistant",
            parts: [{ type: "text", content: e.content }],
          }
          return {
            runtimes: {
              ...state.runtimes,
              [sid]: { ...rt, messages: [...rt.messages, msg] },
            },
          }
        })
      },
      onFinding: ({ finding }) => {
        set((state) => {
          const exists = state.findings.some((f) => f.id === finding.id)
          const findings = exists
            ? state.findings.map((f) => (f.id === finding.id ? finding : f))
            : [finding, ...state.findings]
          return { findings }
        })
      },
      onConnectionChange: (connected) => set({ streamConnected: connected }),
    })
  },

  newSession: async () => {
    const s = await chatProvider.createSession()
    set((state) => ({
      sessions: [...state.sessions, s],
      activeId: s.id,
      runtimes: { ...state.runtimes, [s.id]: emptyRuntime() },
    }))
  },

  switchSession: async (id) => {
    set({ activeId: id })
    // 非活跃会话首次进入：从 history 端点重水合（保留已缓冲则跳过）
    if (!get().runtimes[id]) {
      const hist = await chatProvider.getSessionHistory(id)
      set((state) => ({
        runtimes: {
          ...state.runtimes,
          [id]: { messages: hist, streaming: new Set() },
        },
      }))
    }
  },

  deleteSession: async (id) => {
    await chatProvider.deleteSession(id)
    scratchDelete(id)
    aborts.get(id)?.abort()
    aborts.delete(id)
    set((state) => {
      const sessions = state.sessions.filter((s) => s.id !== id)
      const runtimes = { ...state.runtimes }
      delete runtimes[id]
      const activeId =
        state.activeId === id
          ? (sessions[sessions.length - 1]?.id ?? null)
          : state.activeId
      return { sessions, runtimes, activeId }
    })
  },

  sendMessage: async (text, context) => {
    const sid = get().activeId
    if (!sid) return
    const trimmed = text.trim()
    if (!trimmed) return

    // 1) 预放 user + pending 占位（pendingId 待 TEXT_MESSAGE_START 认领）
    const userMsg: ChatMessage = {
      id: uid("u"),
      role: "user",
      parts: [{ type: "text", content: trimmed }],
    }
    const pendingId = uid("a")
    const scratch = scratchFor(sid)
    scratch.currentMessageId = pendingId
    scratch.localPendingId = pendingId
    set((state) => {
      const rt = state.runtimes[sid] ?? emptyRuntime()
      return {
        runtimes: {
          ...state.runtimes,
          [sid]: {
            messages: [
              ...rt.messages,
              userMsg,
              { id: pendingId, role: "assistant", parts: [{ type: "pending" }] },
            ],
            streaming: new Set([...rt.streaming, pendingId]),
          },
        },
      }
    })

    // 2) 流式消费：ui.open 作 side-effect，其余 reduce 成 parts
    const ctrl = new AbortController()
    aborts.set(sid, ctrl)
    const onEvent = (e: AguiEvent) => {
      if (e.type === "CUSTOM" && e.name === "dataweave.ui.open") {
        const v = e.value as {
          view?: string
          params?: Record<string, unknown>
          activate?: boolean
        }
        if (typeof v.view === "string") {
          useWorkspaceStore
            .getState()
            .open(v.view, v.params, { activate: v.activate })
        }
        return
      }
      set((state) => {
        const rt = state.runtimes[sid] ?? emptyRuntime()
        const next = reduceAguiEvent(rt, e, scratchFor(sid))
        return { runtimes: { ...state.runtimes, [sid]: next } }
      })
    }
    try {
      await chatProvider.sendMessage(
        { sessionId: sid, text: trimmed, context, signal: ctrl.signal },
        onEvent,
      )
    } catch {
      onEvent({ type: "ERROR", code: "CLIENT", message: "Failed to send" })
    } finally {
      aborts.delete(sid)
    }
  },

  cancel: () => {
    const sid = get().activeId
    if (!sid) return
    aborts.get(sid)?.abort()
    aborts.delete(sid)
    const scratch = scratchFor(sid)
    const id = scratch.currentMessageId
    scratch.currentMessageId = null
    scratch.localPendingId = null
    set((state) => {
      const rt = state.runtimes[sid] ?? emptyRuntime()
      const messages = rt.messages
        .filter((m) => !isPendingOnly(m))
        .map((m) =>
          m.id === id
            ? {
                ...m,
                interrupted: true,
                parts: m.parts.map((p) =>
                  p.type === "reasoning" && p.status === "streaming"
                    ? { ...p, status: "done" as const }
                    : p,
                ),
              }
            : m,
        )
      return {
        runtimes: { ...state.runtimes, [sid]: { messages, streaming: new Set() } },
      }
    })
  },

  decidePermission: async (requestId, action) => {
    // 定位持有该 permission 的会话（不一定是当前激活会话）
    let owner: string | null = null
    for (const [sid, rt] of Object.entries(get().runtimes)) {
      if (
        rt.messages.some((m) =>
          m.parts.some(
            (p) => p.type === "permission" && p.requestId === requestId,
          ),
        )
      ) {
        owner = sid
        break
      }
    }
    const sid = owner ?? get().activeId
    if (!sid) return
    // 乐观置为 allowed/denied
    set((state) => {
      const rt = state.runtimes[sid] ?? emptyRuntime()
      const messages = updatePermissionPart(rt.messages, requestId, (p) => ({
        ...p,
        status: action === "approve" ? "allowed" : "denied",
      }))
      return { runtimes: { ...state.runtimes, [sid]: { ...rt, messages } } }
    })
    try {
      const res = await chatProvider.decideApproval(requestId, action)
      set((state) => {
        const rt = state.runtimes[sid] ?? emptyRuntime()
        const messages = updatePermissionPart(rt.messages, requestId, (p) => ({
          ...p,
          resolvedMessage: res.message,
        }))
        return { runtimes: { ...state.runtimes, [sid]: { ...rt, messages } } }
      })
    } catch {
      // 决策失败：回滚为 pending
      set((state) => {
        const rt = state.runtimes[sid] ?? emptyRuntime()
        const messages = updatePermissionPart(rt.messages, requestId, (p) => ({
          ...p,
          status: "pending",
        }))
        return { runtimes: { ...state.runtimes, [sid]: { ...rt, messages } } }
      })
    }
  },

  refreshFindings: async () => {
    set({ findingsLoading: true })
    try {
      const findings = await chatProvider.listFindings()
      set({ findings, findingsLoading: false })
    } catch {
      set({ findingsLoading: false })
    }
  },

  applyFinding: async (id, actionKey) => {
    const result = await chatProvider.applyFinding(id, actionKey)
    // executed → 已 RESOLVED，刷新列表移除；PENDING_APPROVAL/rejected 保留供内联审批
    if (result.outcome === "executed") {
      void get().refreshFindings()
    }
    return result
  },
}))

/** 卸载 agent-stream 订阅（agent-rail 常驻，仅热重载/测试用）。 */
export function disposeChat(): void {
  if (unsubscribeStream) {
    unsubscribeStream()
    unsubscribeStream = null
  }
}
