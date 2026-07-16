/**
 * 虚拟管家前端状态 store（zustand）。
 */
import { create } from "zustand"
import type {
  CompanionState,
  ReportView,
  MessageView,
  Briefing,
} from "./types"

export type ConnectionStatus = "connecting" | "live" | "disconnected"

interface CompanionStore {
  /** 管家形态 */
  state: CompanionState
  /** 注意：命名 avoid "setState" 撞 zustand 内置 */
  setCompanionState: (s: CompanionState) => void

  /** 巡检概况 */
  briefing: Briefing
  setBriefing: (b: Briefing) => void

  /** 汇报列表（未关闭，时间倒序） */
  reports: ReportView[]
  setReports: (r: ReportView[]) => void
  addReport: (r: ReportView) => void
  removeReport: (id: string) => void

  /** 全局消息 */
  messages: MessageView[]
  /** 整表合并写入并按 id 去重（历史加载用）；在途流（较长 content）不被历史短快照覆盖 */
  setMessages: (list: MessageView[]) => void
  /** 幂等追加：已存在 id 则覆盖（在途流优先），否则追加 */
  addMessage: (m: MessageView) => void
  /** 追加流式 chunk 到最新一条消息 */
  appendDelta: (messageId: string, chunk: string) => void
  /** 完成一条流式消息 */
  endMessage: (messageId: string, interrupted: boolean) => void
  /** 进行中的流式消息 id(turnId);null=无在途流(驱动发送-停止状态机与播报) */
  streamingId: string | null

  /** 当前锚定的问题 id（null=全局对话）；驱动 sendChat 是否带 reportId 与线程锚定头 */
  anchorReportId: string | null
  setAnchor: (reportId: string | null) => void

  /** SSE 连接状态 */
  connection: ConnectionStatus
  setConnection: (c: ConnectionStatus) => void
}

export const useCompanionStore = create<CompanionStore>((set) => ({
  state: "idle",
  setCompanionState: (s) => set({ state: s }),

  briefing: { todayRuns: 0, openAnomalies: 0, nextPatrolAt: null },
  setBriefing: (b) => set({ briefing: b }),

  reports: [],
  setReports: (r) => set({ reports: r }),
  addReport: (r) =>
    set((s) => {
      const idx = s.reports.findIndex((x) => x.id === r.id)
      if (idx >= 0) {
        const next = [...s.reports]
        next[idx] = r
        return { reports: next }
      }
      return { reports: [r, ...s.reports] }
    }),
  removeReport: (id) =>
    set((s) => ({
      reports: s.reports.filter((r) => r.id !== id),
      // 当前锚定问题被（他人）关闭 → 回落全局对话
      anchorReportId: s.anchorReportId === id ? null : s.anchorReportId,
    })),

  messages: [],
  setMessages: (list) =>
    set((s) => {
      // 合并 union by id：保留在途流（较长 content）不被历史短快照覆盖
      const byId = new Map<string, MessageView>()
      for (const m of s.messages) byId.set(m.id, m)
      for (const m of list) {
        const prev = byId.get(m.id)
        if (prev && prev.content.length > m.content.length) continue
        byId.set(m.id, m)
      }
      return { messages: Array.from(byId.values()) }
    }),
  addMessage: (m) =>
    set((s) => {
      const idx = s.messages.findIndex((x) => x.id === m.id)
      if (idx < 0) return { messages: [...s.messages, m] }
      // 幂等：已存在（如历史已载入）则覆盖，但在途流较长 content 优先
      const prev = s.messages[idx]
      if (prev.content.length > m.content.length) return { messages: s.messages }
      const next = [...s.messages]
      next[idx] = m
      return { messages: next }
    }),
  appendDelta: (messageId, chunk) =>
    set((s) => {
      const idx = s.messages.findIndex((m) => m.id === messageId)
      if (idx < 0) {
        // delta 可能早于 message 事件到达，创建占位
        const placeholder: MessageView = {
          id: messageId,
          role: "AGENT",
          actorName: "",
          content: chunk,
          createdAt: new Date().toISOString(),
        }
        return { messages: [...s.messages, placeholder], streamingId: messageId }
      }
      const next = [...s.messages]
      next[idx] = { ...next[idx], content: next[idx].content + chunk }
      return { messages: next, streamingId: messageId }
    }),
  endMessage: (messageId, interrupted) =>
    set((s) => {
      const idx = s.messages.findIndex((m) => m.id === messageId)
      if (idx < 0) return { ...s, streamingId: null }
      const next = [...s.messages]
      next[idx] = {
        ...next[idx],
        content: next[idx].content + (interrupted ? " ⌟" : ""),
      }
      return { messages: next, streamingId: null }
    }),
  streamingId: null,

  anchorReportId: null,
  setAnchor: (reportId) => set({ anchorReportId: reportId }),

  connection: "disconnected",
  setConnection: (c) => set({ connection: c }),
}))
