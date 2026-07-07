/**
 * 实例操作按钮状态映射——纯函数、零外部依赖。
 *
 * 状态 → 操作规则（来自 spec 056 FR-009~FR-014 + clarifications）：
 *   RUNNING / DISPATCHED    → 重跑❌  恢复❌  停止✅
 *   SUCCESS                 → 重跑✅  恢复❌  停止❌
 *   FAILED / PREEMPTED      → 重跑✅  恢复✅  停止✅
 *   STOPPED                 → 重跑✅  恢复❌  停止❌
 *   NOT_RUN / WAITING / PAUSED → 重跑❌  恢复❌  停止✅
 */

type ActionKind = "rerun" | "recover" | "stop"

const RERUN_DISABLED = new Set(["RUNNING", "DISPATCHED", "NOT_RUN", "WAITING", "PAUSED"])
const RECOVER_ENABLED = new Set(["FAILED", "PREEMPTED"])
const STOP_DISABLED = new Set(["SUCCESS", "STOPPED"])

/** 返回 true 表示该操作在当前状态下可用。 */
export function isActionEnabled(state: string | undefined | null, action: ActionKind): boolean {
  if (!state) return false
  const s = state.toUpperCase()
  switch (action) {
    case "rerun":
      return !RERUN_DISABLED.has(s)
    case "recover":
      return RECOVER_ENABLED.has(s)
    case "stop":
      return !STOP_DISABLED.has(s)
    default:
      return false
  }
}

/** 批量操作：所有选中行对该操作都可用时返回 true。 */
export function isBulkActionEnabled(
  selectedStates: (string | undefined | null)[],
  action: ActionKind,
): boolean {
  if (selectedStates.length === 0) return false
  return selectedStates.every((s) => isActionEnabled(s, action))
}
