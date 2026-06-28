import { create } from "zustand"

/**
 * 运维中心「Agent 举手台」告警 store（前端本地状态）。
 *
 * 说明：当前告警来源为 mock 注入（ops-view `useMockAlertInjector` 经
 * `window.__MOCK_OPS_ALERT__`）。真实告警推送链路属服务端 AI 时代概念，Weft 掉头后
 * 尚未接入——本 store 仅承载本地展示/消解，是否保留该 rail 为后续产品决策。
 */

export type OpsAlertKind = "INSTANCE_FAILED" | "SLA_RISK" | "BACKFILL_DONE"
export type OpsAlertSeverity = "error" | "warn" | "info"

export interface OpsAlertSuggestedAction {
  op: "rerun" | "kill" | "setSuccess" | "backfill"
  params: Record<string, unknown>
}

export interface OpsAlert {
  id: string
  kind: OpsAlertKind
  severity: OpsAlertSeverity
  title: string
  detail?: string
  instanceIds: string[]
  suggestedAction?: OpsAlertSuggestedAction
  receivedAt: number
  resolved?: boolean
}

interface OpsAlertsState {
  alerts: OpsAlert[]
  /** 推入一条告警；同 id 幂等（已存在则忽略）。 */
  push: (alert: OpsAlert) => void
  /** 标记某告警为已消解。 */
  resolve: (id: string) => void
  clear: () => void
}

export const useOpsAlertsStore = create<OpsAlertsState>()((set) => ({
  alerts: [],
  push: (alert) =>
    set((s) =>
      s.alerts.some((a) => a.id === alert.id) ? s : { alerts: [alert, ...s.alerts] }
    ),
  resolve: (id) =>
    set((s) => ({
      alerts: s.alerts.map((a) => (a.id === id ? { ...a, resolved: true } : a)),
    })),
  clear: () => set({ alerts: [] }),
}))
