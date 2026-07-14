/**
 * 069 监督席直播流归约（纯函数，无 React 依赖，node/vitest 可直接 import）。
 *
 * SSE 事件 → 状态：snapshot/incident/message 为持久化背书（可作真相），
 * thinking/chip/delta 为瞬态智能感层（断线丢失无损）。delta 打字流以对应持久化 message
 * （payload.streamId 匹配）收尾即清空缓冲——前端永远以落库消息为最终真相（SC-005）。
 */
import {
  type ConnectionPhase,
  type Incident,
  type IncidentLiveState,
  type IncidentMessage,
  type IncidentStats,
  type IncidentState,
  isPending,
} from "./types"

export interface SupervisionState {
  /** 事故按 id 索引 */
  incidents: Record<string, Incident>
  /** 每事故线程消息（去重 by seq，升序）——REST 历史 + 直播增量合并 */
  messages: Record<string, IncidentMessage[]>
  /** 每事故瞬态直播态 */
  live: Record<string, IncidentLiveState>
  stats: IncidentStats | null
  briefing: { summaryLine: string | null; generatedAt: string | null } | null
  /** 实时通道状态（US2）：connecting=首帧未达、live=已收 snapshot、degraded=断线重连中。 */
  connectionPhase: ConnectionPhase
  /** 便捷派生：是否处于 live（LiveDot/直播脉冲用）；= connectionPhase === "live"。 */
  connected: boolean
}

export function initialState(): SupervisionState {
  return {
    incidents: {},
    messages: {},
    live: {},
    stats: null,
    briefing: null,
    connectionPhase: "connecting",
    connected: false,
  }
}

export type SupervisionAction =
  | { type: "phase"; value: ConnectionPhase }
  | { type: "connected"; value: boolean }
  | { type: "snapshot"; incidents: Incident[]; stats: IncidentStats | null }
  | { type: "incident"; incident: Incident }
  | { type: "message"; incidentId: string; message: IncidentMessage }
  | { type: "seedMessages"; incidentId: string; messages: IncidentMessage[] }
  | { type: "briefing"; summaryLine: string | null; stats: IncidentStats | null; generatedAt: string | null }
  | { type: "thinking"; incidentId: string; phase: "START" | "STOP"; label: string | null }
  | { type: "chip"; incidentId: string; chipId: string; label: string; status: "RUNNING" | "DONE" | "FAILED" }
  | { type: "delta"; incidentId: string; streamId: string; text: string }

const EMPTY_LIVE: IncidentLiveState = { thinking: { active: false, label: null }, chips: [], delta: null }

function liveOf(state: SupervisionState, id: string): IncidentLiveState {
  return state.live[id] ?? EMPTY_LIVE
}

export function reduce(state: SupervisionState, action: SupervisionAction): SupervisionState {
  switch (action.type) {
    case "phase":
      return { ...state, connectionPhase: action.value, connected: action.value === "live" }

    case "connected":
      // 兼容旧派发：布尔 → 相位（true=live、false=degraded），degraded 不清空消息。
      return {
        ...state,
        connectionPhase: action.value ? "live" : "degraded",
        connected: action.value,
      }

    case "snapshot": {
      const incidents: Record<string, Incident> = {}
      for (const inc of action.incidents) incidents[inc.id] = inc
      // 首帧到达 = 通道确认可信，转 live（此后空 feed 才是真「无事故」）。
      return { ...state, incidents, stats: action.stats ?? state.stats, connectionPhase: "live", connected: true }
    }

    case "incident": {
      const inc = action.incident
      return { ...state, incidents: { ...state.incidents, [inc.id]: inc } }
    }

    case "message": {
      const { incidentId, message } = action
      const merged = appendMessage(state.messages[incidentId] ?? [], message)
      // 若该消息带 streamId 且与当前打字流缓冲匹配 → 收尾清空（落库消息替换打字流分片）
      const live = liveOf(state, incidentId)
      const streamId = readStreamId(message)
      const nextLive: IncidentLiveState =
        live.delta && streamId && live.delta.streamId === streamId
          ? { ...live, delta: null }
          : live
      return {
        ...state,
        messages: { ...state.messages, [incidentId]: merged },
        live: { ...state.live, [incidentId]: nextLive },
      }
    }

    case "seedMessages": {
      let merged = state.messages[action.incidentId] ?? []
      for (const m of action.messages) merged = appendMessage(merged, m)
      return { ...state, messages: { ...state.messages, [action.incidentId]: merged } }
    }

    case "briefing":
      return {
        ...state,
        briefing: { summaryLine: action.summaryLine, generatedAt: action.generatedAt },
        stats: action.stats ?? state.stats,
      }

    case "thinking": {
      const live = liveOf(state, action.incidentId)
      const thinking =
        action.phase === "START"
          ? { active: true, label: action.label }
          : { active: false, label: null }
      return { ...state, live: { ...state.live, [action.incidentId]: { ...live, thinking } } }
    }

    case "chip": {
      const live = liveOf(state, action.incidentId)
      const chips = upsertChip(live.chips, {
        chipId: action.chipId,
        label: action.label,
        status: action.status,
      })
      return { ...state, live: { ...state.live, [action.incidentId]: { ...live, chips } } }
    }

    case "delta": {
      const live = liveOf(state, action.incidentId)
      const prev = live.delta && live.delta.streamId === action.streamId ? live.delta.text : ""
      const delta = { streamId: action.streamId, text: prev + action.text }
      return { ...state, live: { ...state.live, [action.incidentId]: { ...live, delta } } }
    }

    default:
      return state
  }
}

/** 去重追加（by seq，升序）；同 seq 视为已存在（幂等，重连补齐不叠加）。 */
function appendMessage(list: IncidentMessage[], msg: IncidentMessage): IncidentMessage[] {
  if (list.some((m) => m.seq === msg.seq)) return list
  const next = [...list, msg]
  next.sort((a, b) => a.seq - b.seq)
  return next
}

function upsertChip(
  chips: { chipId: string; label: string; status: "RUNNING" | "DONE" | "FAILED" }[],
  chip: { chipId: string; label: string; status: "RUNNING" | "DONE" | "FAILED" },
) {
  const idx = chips.findIndex((c) => c.chipId === chip.chipId)
  if (idx < 0) return [...chips, chip]
  const next = [...chips]
  next[idx] = chip
  return next
}

function readStreamId(msg: IncidentMessage): string | null {
  if (!msg.payloadJson) return null
  try {
    const p = JSON.parse(msg.payloadJson) as { streamId?: string }
    return p.streamId ?? null
  } catch {
    return null
  }
}

// ─── selectors ──────────────────────────────────────────────

export interface FeedFilter {
  /** 只看某状态；null=全部 */
  state?: IncidentState | null
  /** 只看某任务 */
  taskDefId?: number | null
}

/**
 * feed 排序：待处理（AWAITING_APPROVAL/NEEDS_HUMAN）固定置顶（洪峰不被刷走，FR-015），
 * 其余按 openedAt 倒序。返回 { pending, rest } 便于置顶区与滚动流分区渲染。
 */
export function selectFeed(
  state: SupervisionState,
  filter: FeedFilter = {},
): { pending: Incident[]; rest: Incident[] } {
  const all = Object.values(state.incidents).filter((inc) => {
    if (filter.state && inc.state !== filter.state) return false
    if (filter.taskDefId != null && inc.taskDefId !== filter.taskDefId) return false
    return true
  })
  const byOpenedDesc = (a: Incident, b: Incident) =>
    (b.openedAt ?? "").localeCompare(a.openedAt ?? "")
  const pending = all.filter((i) => isPending(i.state)).sort(byOpenedDesc)
  const rest = all.filter((i) => !isPending(i.state)).sort(byOpenedDesc)
  return { pending, rest }
}

export function selectMessages(state: SupervisionState, incidentId: string): IncidentMessage[] {
  return state.messages[incidentId] ?? []
}

export function selectLive(state: SupervisionState, incidentId: string): IncidentLiveState {
  return state.live[incidentId] ?? EMPTY_LIVE
}
