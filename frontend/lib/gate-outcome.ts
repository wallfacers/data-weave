/**
 * 写操作闸门返回的三态归一（029）。
 *
 * 所有资产/指标写操作经服务端 PolicyEngine 闸门,返回 `GateResult.outcome`：
 * EXECUTED（已执行）/ PENDING_APPROVAL（待审批）/ REJECTED（被拒）。
 * 本模块把响应归一为三态(纯函数,可单测),**严守三态如实**(FR-012/SC-005)——
 * 绝不把待审批伪装成成功。
 */

import type { ApiResponse, GateResult } from "./catalog-api"

export type GateResolution =
  | { kind: "executed" }
  | { kind: "pending" }
  | { kind: "failed"; errorCode?: string; backendMessage?: string }

/**
 * 把闸门写返回归一为三态。
 * - code=0 且 outcome=EXECUTED → executed
 * - outcome=PENDING_APPROVAL → pending（无论 code,outcome 已明示待审批）
 * - 其它（REJECTED / code≠0 / 空）→ failed（带 errorCode + 后端 message）
 */
export function resolveGate(res: ApiResponse<GateResult> | null | undefined): GateResolution {
  if (!res) return { kind: "failed" }
  const outcome = res.data?.outcome
  if (res.code === 0 && outcome === "EXECUTED") return { kind: "executed" }
  if (outcome === "PENDING_APPROVAL") return { kind: "pending" }
  return {
    kind: "failed",
    errorCode: res.errorCode ?? undefined,
    backendMessage: res.message,
  }
}

type Translate = (key: string) => string

/**
 * 三态 → toast 文案。已知业务错误码用调用方提供的 i18n key 映射（更友好提示,SC-006）,
 * 未知码回落后端 message,再无则通用失败。调用方的命名空间须含 `actionDone`/`pendingApproval`/`actionFailed`。
 */
export function gateToast(r: GateResolution, t: Translate, errKeys: Record<string, string> = {}): string {
  if (r.kind === "executed") return t("actionDone")
  if (r.kind === "pending") return t("pendingApproval")
  if (r.errorCode && errKeys[r.errorCode]) return t(errKeys[r.errorCode])
  return r.backendMessage && r.backendMessage.trim() ? r.backendMessage : t("actionFailed")
}
