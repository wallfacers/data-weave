/**
 * 070 US4 线程渲染分组（纯函数，无 React/i18n 依赖，vitest 可直接测边界）。
 *
 * 规则：①跨自然日插入 DateSeparator；②相邻同发言者且间隔 ≤5min 并入同组，仅组首 showHeader（带头像+姓名）。
 * 日期标签的「今天/昨天」本地化留给组件（此处只产出 dateKey=YYYY-MM-DD 与 showHeader 标记）。
 */
import type { IncidentMessage } from "./types"

export const GROUP_WINDOW_MS = 5 * 60 * 1000

export type RenderRow =
  | { type: "date"; key: string; dateKey: string }
  | { type: "message"; key: string; msg: IncidentMessage; showHeader: boolean }

/** 发言者分组键：人类按 actor 区分，Agent 三类合一，动作/系统各自一类。 */
function actorKeyOf(m: IncidentMessage): string {
  switch (m.kind) {
    case "HUMAN_SAY":
      return `human:${m.actor ?? ""}`
    case "AGENT_SAY":
    case "AGENT_STEP":
    case "PROPOSAL":
      return "agent"
    case "ACTION":
      return "action"
    default:
      return "system"
  }
}

function dayKeyOf(iso: string | null): string | null {
  return iso ? iso.slice(0, 10) : null
}

function timeOf(iso: string | null): number | null {
  if (!iso) return null
  const t = new Date(iso).getTime()
  return Number.isNaN(t) ? null : t
}

export function groupMessages(messages: IncidentMessage[]): RenderRow[] {
  const rows: RenderRow[] = []
  let prevActorKey: string | null = null
  let prevTime: number | null = null
  let prevDay: string | null = null

  for (const msg of messages) {
    const day = dayKeyOf(msg.createdAt)
    const dateChanged = day !== null && day !== prevDay
    if (dateChanged) {
      rows.push({ type: "date", key: `date-${day}-${msg.seq}`, dateKey: day })
    }
    const ak = actorKeyOf(msg)
    const t = timeOf(msg.createdAt)
    const gap = prevTime !== null && t !== null ? t - prevTime : Number.POSITIVE_INFINITY
    const showHeader = dateChanged || ak !== prevActorKey || gap > GROUP_WINDOW_MS
    rows.push({ type: "message", key: msg.id, msg, showHeader })
    prevActorKey = ak
    prevTime = t
    prevDay = day ?? prevDay
  }
  return rows
}
