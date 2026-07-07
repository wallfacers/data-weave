/**
 * 血缘图累积状态 reducer —— 支持增量展开/收起（FR-005）的节点∪边 merge + dedup + collapse 纯逻辑。
 *
 * 与 lineage-layout 分离：layout 做 dagre 定位 → 渲染；graph 管图数据的增删去重。
 * 收起时仅移除「该次展开独占且不属于基图/其他展开/锚点」的子节点，保护锚点与基图防误删（FR-025 环去重）。
 */
import type { FlowEdgeView, GraphNodeView } from "@/lib/lineage-api"
import { edgeKey } from "./lineage-layout"

export interface LineageGraphState {
  anchorId: string | null
  nodes: GraphNodeView[]
  edges: FlowEdgeView[]
  /** 已增量展开邻居的节点 id 集合。 */
  expanded: Set<string>
  /** 锚点初始加载时的节点集（收起时不误删）。 */
  baseNodeIds: Set<string>
  /**
   * 展开关系簿：nodeId → 该次展开新增的子节点 id（仅当次不重复项）。
   * 收起 nodeId 时从 children[nodeId] 找到候选移除项。
   */
  children: Record<string, string[]>
  truncated: boolean
}

function mergeNodes(existing: GraphNodeView[], incoming: GraphNodeView[]): {
  merged: GraphNodeView[]
  addedIds: string[]
} {
  const ids = new Set(existing.map((n) => n.id))
  const addedIds: string[] = []
  const merged = [...existing]
  for (const n of incoming) {
    if (!ids.has(n.id)) {
      ids.add(n.id)
      merged.push(n)
      addedIds.push(n.id)
    }
  }
  return { merged, addedIds }
}

function mergeEdges(existing: FlowEdgeView[], incoming: FlowEdgeView[]): FlowEdgeView[] {
  const keys = new Set(existing.map(edgeKey))
  const merged = [...existing]
  for (const e of incoming) {
    const k = edgeKey(e)
    if (!keys.has(k)) {
      keys.add(k)
      merged.push(e)
    }
  }
  return merged
}

export type LineageGraphAction =
  | { type: "load"; anchorId: string; nodes: GraphNodeView[]; edges: FlowEdgeView[]; truncated: boolean }
  | { type: "expand"; nodeId: string; nodes: GraphNodeView[]; edges: FlowEdgeView[] }
  | { type: "collapse"; nodeId: string }
  | { type: "reset" }

export function initialGraphState(): LineageGraphState {
  return {
    anchorId: null,
    nodes: [],
    edges: [],
    expanded: new Set(),
    baseNodeIds: new Set(),
    children: {},
    truncated: false,
  }
}

export function lineageGraphReducer(
  state: LineageGraphState,
  action: LineageGraphAction,
): LineageGraphState {
  switch (action.type) {
    case "load":
      return {
        anchorId: action.anchorId,
        nodes: action.nodes,
        edges: action.edges,
        expanded: new Set<string>(),
        baseNodeIds: new Set(action.nodes.map((n) => n.id)),
        children: {},
        truncated: action.truncated,
      }

    case "expand": {
      const { merged: nodes, addedIds } = mergeNodes(state.nodes, action.nodes)
      const edges = mergeEdges(state.edges, action.edges)
      const expanded = new Set(state.expanded)
      expanded.add(action.nodeId)
      const children = { ...state.children }
      children[action.nodeId] = addedIds
      return { ...state, nodes, edges, expanded, children }
    }

    case "collapse": {
      const kids = state.children[action.nodeId] ?? []
      if (kids.length === 0) {
        // 无子节点记录 → 仅从 expanded 移除（可能为预展开空态）
        const exp2 = new Set(state.expanded)
        exp2.delete(action.nodeId)
        const ch2 = { ...state.children }
        delete ch2[action.nodeId]
        return { ...state, expanded: exp2, children: ch2 }
      }

      // 受保护集合：锚点 + 基图节点 + 其他已展开节点的子节点
      const protectedSet = new Set(state.baseNodeIds)
      if (state.anchorId) protectedSet.add(state.anchorId)
      for (const [pid, cids] of Object.entries(state.children)) {
        if (pid !== action.nodeId) cids.forEach((c) => protectedSet.add(c))
      }

      const removeSet = new Set(kids.filter((k) => !protectedSet.has(k)))
      const nodes = state.nodes.filter((n) => !removeSet.has(n.id))
      const edges = state.edges.filter(
        (e) => !removeSet.has(e.from) && !removeSet.has(e.to),
      )
      const expanded = new Set(state.expanded)
      expanded.delete(action.nodeId)
      const children = { ...state.children }
      delete children[action.nodeId]
      return { ...state, nodes, edges, expanded, children }
    }

    case "reset":
      return initialGraphState()

    default:
      return state
  }
}
