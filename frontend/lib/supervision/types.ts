/**
 * 067 监督席指挥中心 —— 领域类型（镜像后端 record，camelCase）。
 * 纯类型，无 React 依赖，可在 node 测试环境直接 import。
 */

/** 事故状态（后端 IncidentStates）。 */
export type IncidentState =
  | "OPEN"
  | "ANALYZING"
  | "ACTING"
  | "AWAITING_APPROVAL"
  | "NEEDS_HUMAN"
  | "RESOLVED"
  | "DIAG_UNAVAILABLE"

/** 分型（后端 IncidentClassifications）；null=未诊断。 */
export type IncidentClassification =
  | "TRANSIENT"
  | "RESOURCE"
  | "CODE"
  | "UPSTREAM_DATA"
  | "CONFIG_CREDENTIAL"
  | "UNKNOWN"

/** 线程消息种类（后端 MessageKinds）。 */
export type MessageKind =
  | "AGENT_STEP"
  | "AGENT_SAY"
  | "HUMAN_SAY"
  | "ACTION"
  | "PROPOSAL"
  | "SYSTEM"

export interface Incident {
  id: string
  tenantId: number
  projectId: number
  taskDefId: number
  taskDefName: string | null
  firstInstanceId: string
  latestInstanceId: string
  instanceCount: number
  triggerSource: string | null
  classification: IncidentClassification | null
  confidence: string | null
  state: IncidentState
  openKey: number | null
  autoActionCount: number
  summary: string | null
  suggestion: string | null
  closeKind: string | null
  openedAt: string | null
  closedAt: string | null
  version: number
  createdAt: string | null
  updatedAt: string | null
}

export interface IncidentMessage {
  id: string
  incidentId: string
  seq: number
  kind: MessageKind
  content: string | null
  payloadJson: string | null
  actor: string | null
  createdAt: string | null
}

export interface IncidentProposal {
  id: string
  incidentId: string
  taskDefId: number
  baseVersionNo: number
  proposedContent: string
  changeSummary: string | null
  evidenceJson: string | null
  status: string
  agentActionId: number | null
  publishedVersionNo: number | null
  rollbackVersionNo: number | null
  approvedBy: number | null
  approvedAt: string | null
  createdAt: string | null
  updatedAt: string | null
}

/** 实时数字（后端 IncidentStats，SC-010 唯一权威）。 */
export interface IncidentStats {
  active: number
  agentWorking: number
  awaitingApproval: number
  needsHuman: number
  resolvedToday: number
}

export interface BriefingView {
  summaryLine: string | null
  stats: IncidentStats
  reportMd: string | null
  generatedAt: string | null
}

export interface IncidentDetail {
  incident: Incident
  proposals: IncidentProposal[]
  messageCount: number
}

// ─── 瞬态直播事件（智能感层，不落库、不重放）──────────────────

/** 进行中的一次思考态：label 非空=思考中。 */
export interface ThinkingState {
  active: boolean
  label: string | null
}

/** 工具动作点亮 chip。 */
export interface ChipState {
  chipId: string
  label: string
  status: "RUNNING" | "DONE" | "FAILED"
}

/** 流式打字缓冲（delta 分片拼接，收到对应持久化 message 即清空）。 */
export interface DeltaBuffer {
  streamId: string
  text: string
}

/** 某个事故的瞬态直播态（feed 卡片/线程消费）。 */
export interface IncidentLiveState {
  thinking: ThinkingState
  chips: ChipState[]
  delta: DeltaBuffer | null
}

/** 首屏待处理判定：需要人类的两类状态固定置顶。 */
export const PENDING_STATES: ReadonlySet<IncidentState> = new Set<IncidentState>([
  "AWAITING_APPROVAL",
  "NEEDS_HUMAN",
])

/** Agent 正在处理（呼吸动效触发条件）。 */
export const AGENT_WORKING_STATES: ReadonlySet<IncidentState> = new Set<IncidentState>([
  "OPEN",
  "ANALYZING",
  "ACTING",
])

export function isPending(state: IncidentState): boolean {
  return PENDING_STATES.has(state)
}

export function isAgentWorking(state: IncidentState): boolean {
  return AGENT_WORKING_STATES.has(state)
}

export function isTerminal(state: IncidentState): boolean {
  return state === "RESOLVED"
}
