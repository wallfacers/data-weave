/**
 * ChatProvider —— 自研聊天台与后端（或 mock）的唯一接缝（proactive-agent-discovery §D4/§D5）。
 *
 * 上层 store / 组件只依赖这个接口；real 与 mock 各一份实现，经 mode.ts 开关切换。
 * 这样后端 🅰 未就绪时前端用 mock 自洽，合流后置 NEXT_PUBLIC_CHAT_MOCK=0 即对真接口，
 * 上层零改动。
 */
import type {
  AgentPageContext,
  AgentSession,
  AgentStreamHandlers,
  AguiEvent,
  ApplyResult,
  ChatAttachment,
  ChatFileRef,
  ChatMessage,
  EntityOption,
  EntityRefType,
  Finding,
} from "./types"
import { CHAT_USE_MOCK } from "./mode"
import { realChatProvider } from "./real"
import { mockChatProvider } from "./mock"

export interface SendMessageArgs {
  sessionId: string
  text: string
  context?: AgentPageContext
  /** 本轮附件（实体引用 / 上传文件），经 forwardedProps.dataweave.attachments 送达后端。 */
  attachments?: ChatAttachment[]
  /** 取消信号（用户中断本轮）。 */
  signal?: AbortSignal
}

export interface ApprovalDecisionResult {
  success: boolean
  message?: string
}

export interface ChatProvider {
  /** 用户发问：流式消费 AG-UI 事件，逐事件经 `emit` 回写（store 据此 apply parts）。 */
  sendMessage(
    args: SendMessageArgs,
    emit: (e: AguiEvent) => void,
  ): Promise<void>
  /** 持久订阅 GET /api/agent/stream；返回取消订阅函数。 */
  subscribeAgentStream(handlers: AgentStreamHandlers): () => void
  /** 举手台列表（§D5 GET /api/findings，默认 OPEN/ANNOUNCED）。 */
  listFindings(): Promise<Finding[]>
  /** 一键修复（§D5 POST /api/findings/{id}/apply），按 outcome 分流。 */
  applyFinding(id: number | string, actionKey: string): Promise<ApplyResult>
  /** permission 内联审批决策（POST /api/approvals/{id}/{approve|reject}）。 */
  decideApproval(
    requestId: number | string,
    action: "approve" | "reject",
    confirmation?: string,
  ): Promise<ApprovalDecisionResult>
  /** 多会话列表（§D5 GET /api/agent/sessions）。 */
  listSessions(): Promise<AgentSession[]>
  /** 新建会话（§D5 POST /api/agent/sessions）。 */
  createSession(title?: string): Promise<AgentSession>
  /** 删除会话（§D5 DELETE /api/agent/sessions/{id}）。 */
  deleteSession(id: string): Promise<void>
  /** 会话历史重水合（§D5 GET /api/agent/sessions/{id}/history）。 */
  getSessionHistory(id: string): Promise<ChatMessage[]>
  /** 上传聊天附件文件（POST /api/chat/files multipart）→ 可引用的文件元数据。 */
  uploadFile(file: File): Promise<ChatFileRef>
  /** 搜索可附加的平台实体（任务/实例/发现/数据源），供输入框实体选择器。 */
  searchEntities(refType: EntityRefType, keyword: string): Promise<EntityOption[]>
}

export const chatProvider: ChatProvider = CHAT_USE_MOCK
  ? mockChatProvider
  : realChatProvider
