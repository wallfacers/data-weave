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
  addMessage: (m: MessageView) => void
  /** 追加流式 chunk 到最新一条消息 */
  appendDelta: (messageId: string, chunk: string) => void
  /** 完成一条流式消息 */
  endMessage: (messageId: string, interrupted: boolean) => void

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
    set((s) => ({ reports: s.reports.filter((r) => r.id !== id) })),

  messages: [],
  addMessage: (m) =>
    set((s) => ({ messages: [...s.messages, m] })),
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
        return { messages: [...s.messages, placeholder] }
      }
      const next = [...s.messages]
      next[idx] = { ...next[idx], content: next[idx].content + chunk }
      return { messages: next }
    }),
  endMessage: (messageId, interrupted) =>
    set((s) => {
      const idx = s.messages.findIndex((m) => m.id === messageId)
      if (idx < 0) return s
      const next = [...s.messages]
      next[idx] = {
        ...next[idx],
        content: next[idx].content + (interrupted ? " ⌟" : ""),
      }
      return { messages: next }
    }),

  connection: "disconnected",
  setConnection: (c) => set({ connection: c }),
}))
