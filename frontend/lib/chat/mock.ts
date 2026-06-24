/**
 * Mock ChatProvider —— 后端 🅰 主动发现链路未就绪时的前端自洽实现（§D5）。
 *
 * 在内存里模拟：多会话、AG-UI 流式回复、Agent 持久流主动开口与发现冒泡、Findings
 * 列表与闸门 outcome 分流（executed / PENDING_APPROVAL）。让"聊天台渲染输入框、对 mock
 * 流式出字、举手台渲染 mock Finding、Agent 主动开口"独立成立，合流后由 mode.ts 切到 real。
 */
"use client"

import {
  type AgentSession,
  type AgentStreamHandlers,
  type AguiEvent,
  type ApplyResult,
  type ChatMessage,
  type Finding,
} from "./types"
import type { ChatProvider, SendMessageArgs } from "./provider"

// ─── helpers ──────────────────────────────────────────────

let seq = 0
const nid = (p: string) => `${p}-${Date.now()}-${(++seq).toString(36)}`
const nowIso = () => new Date().toISOString()
const delay = (ms: number) => new Promise<void>((r) => setTimeout(r, ms))

function chunkText(text: string): string[] {
  // 按字符切片模拟流式 token（中文逐字、英文按词聚合）。
  const out: string[] = []
  let buf = ""
  for (const ch of text) {
    if (/[A-Za-z0-9]/.test(ch)) {
      buf += ch
    } else {
      if (buf) {
        out.push(buf)
        buf = ""
      }
      out.push(ch)
    }
  }
  if (buf) out.push(buf)
  return out
}

function seedFinding(over: Partial<Finding> & { id: number | string }): Finding {
  return {
    source: "TASK_FAILURE",
    severity: "WARN",
    targetType: "TASK_INSTANCE",
    targetId: "",
    title: "",
    rootCause: "",
    evidence: {},
    actions: [],
    status: "OPEN",
    announced: false,
    createdAt: nowIso(),
    updatedAt: nowIso(),
    ...over,
  }
}

// ─── 内存状态 ─────────────────────────────────────────────

const sessions = new Map<string, AgentSession>()
const histories = new Map<string, ChatMessage[]>()
let findingsState: Finding[] = [
  seedFinding({
    id: 1001,
    severity: "CRITICAL",
    targetId: "inst-20260624-order-sync",
    title: "order_sync 任务失败（OOM）",
    rootCause:
      "节点 node-3 内存争抢，峰值 mem 95%，任务被 OOM Killer 终止。近 7 天该节点同类失败 3 次。",
    evidence: {
      task: "order_sync",
      taskId: 42,
      workerNode: "node-3",
      nodeMem: "95%",
      nodeCpu: "62%",
      concurrentTasks: 8,
      history7d: 3,
      lastError: "java.lang.OutOfMemoryError: Java heap space",
    },
    actions: [
      { key: "RERUN", label: "立即重跑", actionType: "RERUN" },
      { key: "MIGRATE_NODE", label: "迁移到 node-1", actionType: "MIGRATE_NODE" },
      { key: "RERUN_MORE_MEMORY", label: "加大内存重跑", actionType: "RERUN_MORE_MEMORY" },
    ],
  }),
  seedFinding({
    id: 1002,
    severity: "WARN",
    targetId: "inst-20260624-dim-user",
    title: "dim_user 任务连续 2 次失败",
    rootCause: "上游表 dwd_user_log 延迟就绪，任务启动时源表为空。建议调整调度依赖或加等待。",
    evidence: {
      task: "dim_user",
      taskId: 58,
      workerNode: "node-1",
      history7d: 2,
      upstream: "dwd_user_log 未就绪",
    },
    actions: [{ key: "RERUN", label: "立即重跑", actionType: "RERUN" }],
  }),
]

/** 默认会话 id 复用 workspace 持久化的 conversation id，使对话与工作区快照指向同一会话。 */
function defaultSessionId(): string {
  let id =
    typeof window !== "undefined"
      ? localStorage.getItem("dw.conversationId")
      : null
  if (!id) {
    id = nid("sess")
    try {
      localStorage.setItem("dw.conversationId", id)
    } catch {
      // localStorage 不可用 —— 仅内存
    }
  }
  return id
}

function ensureSeed(): void {
  if (sessions.size === 0) {
    const id = defaultSessionId()
    sessions.set(id, { id, title: "当前会话", createdAt: nowIso() })
    if (!histories.has(id)) histories.set(id, [])
  }
}

// ─── 流式回复：按关键词简单分支，覆盖文本/结果表/视图召唤/审批 ───────────────

interface MockReply {
  text: string
  result?: { kind?: string; title?: string; columns: string[]; rows: Record<string, unknown>[] }
  uiOpen?: { view: string; params?: Record<string, unknown> }
  approval?: { approvalId: string; level: string; summary: string; requiresConfirmation?: boolean }
}

function mockReply(input: string): MockReply {
  const s = input.toLowerCase()
  if (/失败|诊断|fail|diagnos|oom|排查/.test(s)) {
    return {
      text:
        "我查了一下，最近一次失败是 **order_sync**：节点 `node-3` 内存 95% 触发 OOM，近 7 天同类失败 3 次。\n\n建议优先 **迁移到 node-1** 或 **加大内存重跑**。要不要我直接帮你处理？相关发现也已上举手台。",
      result: {
        kind: "diagnosis",
        title: "近期失败实例",
        columns: ["task", "node", "state", "history7d"],
        rows: [
          { task: "order_sync", node: "node-3", state: "FAILED", history7d: 3 },
          { task: "dim_user", node: "node-1", state: "FAILED", history7d: 2 },
        ],
      },
      uiOpen: { view: "cockpit" },
    }
  }
  if (/重跑|rerun|迁移|migrate/.test(s)) {
    return {
      text: "重跑属于写操作，需要 L2 审批。我已为你创建审批单，确认后即执行。",
      approval: {
        approvalId: `appr-${Date.now()}`,
        level: "L2",
        summary: "重跑 order_sync（迁移到 node-1）",
      },
    }
  }
  if (/ Fleet|节点|fleet/.test(input)) {
    return {
      text: "当前集群节点状态如下，node-3 负载偏高。",
      result: {
        kind: "fleet",
        title: "集群节点",
        columns: ["node", "cpu", "mem", "running"],
        rows: [
          { node: "node-1", cpu: "31%", mem: "45%", running: 3 },
          { node: "node-2", cpu: "88%", mem: "70%", running: 5 },
          { node: "node-3", cpu: "62%", mem: "95%", running: 8 },
        ],
      },
      uiOpen: { view: "fleet" },
    }
  }
  return {
    text:
      "你好，我是 DataWeave Agent。我可以帮你诊断失败任务、查看集群节点、重跑实例等。试试问我「最近有什么失败」。",
  }
}

// ─── provider ─────────────────────────────────────────────

export const mockChatProvider: ChatProvider = {
  async sendMessage(args: SendMessageArgs, emit: (e: AguiEvent) => void): Promise<void> {
    ensureSeed()
    const sid = args.sessionId
    const hist = histories.get(sid) ?? []
    hist.push({ id: nid("u"), role: "user", parts: [{ type: "text", content: args.text }] })
    histories.set(sid, hist)

    const runId = nid("run")
    const msgId = nid("m")
    emit({ type: "RUN_STARTED", runId, threadId: sid })
    await delay(140)
    emit({ type: "TEXT_MESSAGE_START", messageId: msgId })

    const reply = mockReply(args.text)
    for (const tk of chunkText(reply.text)) {
      if (args.signal?.aborted) {
        emit({ type: "RUN_FINISHED", runId })
        return
      }
      emit({ type: "TEXT_MESSAGE_CONTENT", messageId: msgId, delta: tk })
      await delay(26)
    }
    emit({ type: "TEXT_MESSAGE_END", messageId: msgId })

    if (reply.result) {
      emit({ type: "CUSTOM", name: "dataweave.result", value: reply.result })
    }
    if (reply.uiOpen) {
      emit({ type: "CUSTOM", name: "dataweave.ui.open", value: reply.uiOpen })
    }
    if (reply.approval) {
      emit({ type: "CUSTOM", name: "dataweave.approval", value: reply.approval })
    }
    emit({ type: "RUN_FINISHED", runId })

    // 累积 assistant 回复进 history（重开水合可见）
    const h2 = histories.get(sid) ?? []
    h2.push({ id: msgId, role: "assistant", parts: [{ type: "text", content: reply.text }] })
    histories.set(sid, h2)
  },

  subscribeAgentStream(handlers: AgentStreamHandlers): () => void {
    ensureSeed()
    let cancelled = false
    handlers.onConnectionChange?.(true)

    const keep = window.setInterval(() => {
      if (!cancelled) handlers.onKeepalive?.()
    }, 25000)

    // 主动开口：挂载 6s 后投递一条 agent.message（无人发问）
    const t1 = window.setTimeout(() => {
      if (cancelled) return
      handlers.onMessage({
        sessionId: defaultSessionId(),
        findingId: 1001,
        content:
          "我刚发现 **order_sync** 任务失败了：节点 node-3 内存 95% 触发 OOM，近 7 天同类失败 3 次。要我帮你重跑，或迁移到 node-1 吗？",
      })
    }, 6000)

    // 新发现冒泡：12s 后巡检产出一个 CRITICAL finding，刷新举手台
    const t2 = window.setTimeout(() => {
      if (cancelled) return
      const fresh = seedFinding({
        id: 1003,
        severity: "CRITICAL",
        announced: true,
        status: "ANNOUNCED",
        targetId: "inst-20260624-payment-etl",
        title: "payment_etl 任务失败（资源争抢）",
        rootCause: "node-2 上 5 个任务并发，cpu 88%，本任务超时被杀。",
        evidence: {
          task: "payment_etl",
          taskId: 77,
          workerNode: "node-2",
          nodeCpu: "88%",
          concurrentTasks: 5,
          history7d: 1,
        },
        actions: [
          { key: "RERUN", label: "立即重跑", actionType: "RERUN" },
          { key: "CAP_NODE_WEIGHT", label: "限制节点并发", actionType: "CAP_NODE_WEIGHT" },
        ],
      })
      findingsState = [fresh, ...findingsState]
      handlers.onFinding({ finding: fresh })
    }, 12000)

    return () => {
      cancelled = true
      window.clearInterval(keep)
      window.clearTimeout(t1)
      window.clearTimeout(t2)
      handlers.onConnectionChange?.(false)
    }
  },

  async listFindings(): Promise<Finding[]> {
    await delay(120)
    return findingsState
      .filter((f) => f.status !== "RESOLVED")
      .map((f) => ({ ...f }))
  },

  async applyFinding(id, actionKey): Promise<ApplyResult> {
    await delay(650)
    // 演示闸门分流：迁移/限流这类较重操作走 L2 审批，其余直接执行
    if (actionKey === "MIGRATE_NODE" || actionKey === "CAP_NODE_WEIGHT") {
      return {
        executed: false,
        message: "该操作需 L2 审批，已创建审批单。",
        outcome: "PENDING_APPROVAL",
        approvalId: `appr-${id}-${actionKey}`,
      }
    }
    findingsState = findingsState.map((f) =>
      f.id === id ? { ...f, status: "RESOLVED" } : f,
    )
    return {
      executed: true,
      message: `已执行「${actionKey}」，新实例已下发。`,
      outcome: "executed",
      newInstanceId: 2000 + (typeof id === "number" ? id : 0),
    }
  },

  async decideApproval(requestId, action) {
    await delay(500)
    return {
      success: true,
      message: action === "approve" ? "已批准并执行。" : "已拒绝。",
    }
  },

  async listSessions(): Promise<AgentSession[]> {
    ensureSeed()
    return [...sessions.values()].sort((a, b) =>
      a.createdAt.localeCompare(b.createdAt),
    )
  },

  async createSession(title): Promise<AgentSession> {
    const id = nid("sess")
    const s: AgentSession = { id, title: title ?? "新会话", createdAt: nowIso() }
    sessions.set(id, s)
    histories.set(id, [])
    return s
  },

  async deleteSession(id): Promise<void> {
    sessions.delete(id)
    histories.delete(id)
  },

  async getSessionHistory(id): Promise<ChatMessage[]> {
    return histories.has(id) ? [...(histories.get(id) as ChatMessage[])] : []
  },
}
