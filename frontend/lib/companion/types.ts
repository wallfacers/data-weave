/**
 * 虚拟管家数据层类型（对齐 contracts/companion-api.md）。
 */

/** 管家形态 */
export type CompanionState = "idle" | "patrol" | "alert" | "think" | "speak"

/** 严重度 */
export type ReportSeverity = "DANGER" | "WARN" | "OK" | "INFO"

/** 巡检领域 */
export type PatrolDomain =
  | "TASK_FAILURE"
  | "MACHINE_STATUS"
  | "DATA_QUALITY"
  | "CODE_QUALITY"

/** 汇报状态 */
export type ReportStatus = "UNREAD" | "READ" | "CLOSED"

/** 汇报视图 */
export interface ReportView {
  id: string
  domain: PatrolDomain
  severity: ReportSeverity
  title: string
  summary: string
  detail?: string
  aggregateCount: number
  status: ReportStatus
  closedBy?: string
  createdAt: string
}

/** 消息角色 */
export type MessageRole = "USER" | "AGENT"

/** 消息视图 */
export interface MessageView {
  id: string
  reportId?: string
  role: MessageRole
  actorName: string
  content: string
  createdAt: string
}

/** 巡检概况 */
export interface Briefing {
  todayRuns: number
  openAnomalies: number
  nextPatrolAt: string | null
}

/** SSE snapshot 事件数据 */
export interface SnapshotData {
  state: CompanionState
  briefing: Briefing
  reports: ReportView[]
}

/** SSE state 事件数据 */
export interface StateEvent {
  state: CompanionState
  reason?: string
}

/** SSE report 事件数据 */
export interface ReportEvent {
  type: "created" | "closed"
  report: ReportView
}

/** SSE delta 事件数据 */
export interface DeltaEvent {
  messageId: string
  chunk: string
}

/** SSE end 事件数据 */
export interface EndEvent {
  messageId: string
  interrupted: boolean
}

/** 巡检例程 */
export interface PatrolRoutine {
  id: string
  domain: PatrolDomain
  enabled: boolean
  cronExpression: string
  scopeJson?: unknown
}

/** 巡检执行记录 — 契约 RunView(75d301ce 冻结):耗时由 startedAt/finishedAt 前端派生 */
export interface PatrolRun {
  id: string
  triggerType: string
  state: string
  scheduledFireTime?: string
  startedAt?: string
  finishedAt?: string
  summary?: string
  error?: string
  reportIds?: string[]
}
