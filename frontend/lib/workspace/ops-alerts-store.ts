/**
 * Agent 运维举手台状态：接收 `dataweave.ops.alert` AG-UI 自定义事件，
 * 由 ops-view 右栏渲染为卡片。agent-chat.tsx 的 onCustomEvent 向此 store 推送。
 */
import { create } from "zustand"

export type OpsAlertKind = "INSTANCE_FAILED" | "SLA_RISK" | "BACKFILL_DONE"
export type OpsAlertSeverity = "info" | "warning" | "error"
export type OpsSuggestedOp = "rerun" | "kill" | "set-success" | "backfill"

export interface OpsAlert {
  id: string
  kind: OpsAlertKind
  severity: OpsAlertSeverity
  title: string
  detail?: string
  instanceIds: string[]
  suggestedAction?: { op: OpsSuggestedOp; params: Record<string, unknown> }
  receivedAt: number
  /** 用户/agent 已处置：标记后从活跃列表淡出 */
  resolved?: boolean
}

interface OpsAlertsState {
  alerts: OpsAlert[]
  push: (alert: OpsAlert) => void
  resolve: (id: string) => void
  dismiss: (id: string) => void
  clear: () => void
}

const MAX_ALERTS = 50

export const useOpsAlertsStore = create<OpsAlertsState>()((set) => ({
  alerts: [],
  push: (alert) =>
    set((s) => {
      // 同 id 去重（同一告警不重复推）
      if (s.alerts.some((a) => a.id === alert.id)) return s
      const next = [alert, ...s.alerts].slice(0, MAX_ALERTS)
      return { alerts: next }
    }),
  resolve: (id) =>
    set((s) => ({
      alerts: s.alerts.map((a) => (a.id === id ? { ...a, resolved: true } : a)),
    })),
  dismiss: (id) => set((s) => ({ alerts: s.alerts.filter((a) => a.id !== id) })),
  clear: () => set({ alerts: [] }),
}))
