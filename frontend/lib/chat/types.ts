/**
 * 自研多会话聊天台共享类型（proactive-agent-discovery §D4）。
 *
 * MessagePart / ChatMessage / ChatRuntime 套用 workhorse-assistant 的模型
 * （该仓 src/session/types.ts），适配 DataWeave 的 AG-UI 协议（SCREAMING_SNAKE_CASE
 * 事件 → parts）。Finding 为通用主动发现窄腰（§D1）；AgentSession / ApplyResult /
 * AgentStream 事件为 §D5 冻结契约（与 🅰 后端唯一接缝）。
 */

// ─── 消息 parts ───────────────────────────────────────────

export type MessagePart =
  | { type: "text"; content: string }
  | { type: "reasoning"; text: string; status: "streaming" | "done" }
  | {
      type: "tool_call"
      id: string
      name: string
      input: unknown
      status: "running" | "done" | "error"
      output?: unknown
    }
  | { type: "error"; code: string; message: string }
  | {
      type: "permission"
      /** 唯一标识，兼作审批决策 id（后端 approvalId）。 */
      requestId: string
      tool?: string
      resource?: string
      dangerous?: boolean
      reason?: string
      level?: string
      summary?: string
      status: "pending" | "allowed" | "denied"
      /** 决策完成后的后端回执文案，决策后内联展示。 */
      resolvedMessage?: string
    }
  | { type: "result"; data: ResultData }
  | { type: "pending" }

export interface ChatMessage {
  id: string
  role: "user" | "assistant"
  parts: MessagePart[]
  /** 该轮被用户取消（RUN 未正常结束）。 */
  interrupted?: boolean
}

/** 单会话活动缓冲：消息列表 + 仍在流式中的 assistant 消息 id 集合。 */
export interface ChatRuntime {
  messages: ChatMessage[]
  streaming: Set<string>
}

export const emptyRuntime = (): ChatRuntime => ({
  messages: [],
  streaming: new Set(),
})

/** 真实内容到达时丢弃前置 `pending` 占位 part。 */
export const dropPending = (parts: MessagePart[]): MessagePart[] =>
  parts.filter((p) => p.type !== "pending")

/** 该 assistant 消息是否仅剩首 token 占位（尚无内容）。 */
export const isPendingOnly = (m: ChatMessage): boolean =>
  m.role === "assistant" && m.parts.length === 1 && m.parts[0].type === "pending"

// ─── 结果表（dataweave.result / fleet 等 CUSTOM 富渲染）─────────────────────

export interface ResultData {
  id: number
  kind?: string
  title?: string
  sql?: string
  columns: string[]
  rows: Record<string, unknown>[]
}

// ─── 逐消息页面上下文（cockpit 缺口①：随对话送达后端 forwardedProps）────────

export interface AgentPageContext {
  module?: string
  pathname?: string
  taskId?: string
  instanceId?: string
  nodeId?: string
}

// ─── AG-UI 事件（POST /agui 响应流；字段与后端 AguiEvents.java 对齐）─────────
//
// 后端序列化（camelCase）：RUN_STARTED{threadId,runId}、TEXT_MESSAGE_START{messageId,role}、
// TEXT_MESSAGE_CONTENT{messageId,delta}、TEXT_MESSAGE_END{messageId}、CUSTOM{name,value}、
// RUN_FINISHED{threadId,runId,outcome}。SSE 为标准 `data: <json>`，type 在 JSON 内。
export type AguiEvent =
  | { type: "RUN_STARTED"; runId?: string; threadId?: string }
  | { type: "TEXT_MESSAGE_START"; messageId: string }
  | { type: "TEXT_MESSAGE_CONTENT"; messageId: string; delta: string }
  | { type: "TEXT_MESSAGE_END"; messageId: string }
  | { type: "CUSTOM"; name: string; value: unknown }
  | { type: "ERROR"; code: string; message: string }
  | { type: "RUN_FINISHED"; runId?: string }

// ─── Finding（§D1 通用发现窄腰；evidence/actions 已由 provider 解析）─────────

export type FindingSeverity = "INFO" | "WARN" | "CRITICAL"
export type FindingStatus = "OPEN" | "ANNOUNCED" | "RESOLVED"

export interface FindingAction {
  key: string
  label: string
  actionType?: string
}

export interface Finding {
  id: number | string
  source: string
  severity: FindingSeverity
  targetType: string
  targetId: string
  title: string
  rootCause: string
  /** 已解析的证据（provider 从后端 evidenceJson 解析；缺失为空对象）。 */
  evidence: Record<string, unknown>
  /** 已解析的动作（provider 从后端 actionsJson 解析；缺失为空数组）。 */
  actions: FindingAction[]
  status: FindingStatus
  announced: boolean
  createdAt: string
  updatedAt: string
}

// ─── AgentSession（§D5 多会话）──────────────────────────────────────────────

export interface AgentSession {
  id: string
  title: string
  createdAt: string
}

// ─── ApplyResult（POST /api/findings/{id}/apply，§D5）────────────────────────

export type ApplyOutcome = "executed" | "PENDING_APPROVAL" | "rejected"

export interface ApplyResult {
  executed: boolean
  message: string
  outcome: ApplyOutcome
  newInstanceId?: number | null
  /** outcome=PENDING_APPROVAL 时返回的审批单 id，供内联审批。 */
  approvalId?: number | string | null
}

// ─── Agent 持久流事件（GET /api/agent/stream，§D5，事件名小写点分）────────────

/** agent.message：Agent 主动开口（无人发问），push 进目标会话。 */
export interface AgentMessageEvent {
  /** 目标会话；缺省投递到当前激活会话。 */
  sessionId?: string
  /** 关联的发现 id（可选，用于"我发现 X 失败了"溯源）。 */
  findingId?: number | string
  title?: string
  content: string
}

/** agent.finding：新发现冒泡，刷新举手台。 */
export interface AgentFindingEvent {
  finding: Finding
}

export interface AgentStreamHandlers {
  onMessage: (e: AgentMessageEvent) => void
  onFinding: (e: AgentFindingEvent) => void
  onKeepalive?: () => void
  /** 连接状态变化（含断线重连），用于 UI 指示。 */
  onConnectionChange?: (connected: boolean) => void
}
