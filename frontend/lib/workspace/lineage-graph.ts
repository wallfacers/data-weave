/**
 * 血缘图累积状态 reducer —— 两个独立展开维度的纯逻辑。
 *
 *   ① 邻居增量（双击，FR-005）：`expand`/`collapse` + `expanded`/`children`——原地追加相邻一跳
 *      画布节点/边，保留既有节点与视图；收起仅移除该次独占且非基图/锚点/其他展开的节点（FR-025 环去重）。
 *   ② 列展开（chevron，FR-015）：`expandColumns`/`collapseColumns` + `columnsByTable`——表节点内联列清单
 *      （不新增画布节点；列到列派生边走「列级」粒度切换）。驱动 chevron 指示 + 节点 data.columns 渲染。
 *
 * 与 lineage-layout 分离：layout 做 dagre 定位 → 渲染；graph 管图数据的增删去重。
 */
import type { FlowEdgeView, GraphNodeView, LineageColumnItem } from "@/lib/lineage-api"
import { edgeKey } from "./lineage-layout"

export interface LineageGraphState {
  anchorId: string | null
  nodes: GraphNodeView[]
  edges: FlowEdgeView[]
  /** 已邻居增量展开（双击）的节点 id 集合。 */
  expanded: Set<string>
  /** 锚点初始加载时的节点集（收起时不误删）。 */
  baseNodeIds: Set<string>
  /**
   * 邻居展开关系簿：nodeId → 该次展开新增的子节点 id（仅当次不重复项）。
   * 收起 nodeId 时从 children[nodeId] 找到候选移除项。
   */
  children: Record<string, string[]>
  /** 列展开（内联）：tableId → 该表列清单（含是否参与列级血缘 hasLineage）。 */
  columnsByTable: Record<string, LineageColumnItem[]>
  /** 054：列级派生边（tableId → FlowEdgeView[]，granularity=COLUMN），驱动列→列连线（FR-012/013）。 */
  columnEdgesByTable: Record<string, FlowEdgeView[]>
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
  | { type: "expandColumns"; tableId: string; columns: LineageColumnItem[]; columnEdges?: FlowEdgeView[] }
  | { type: "collapseColumns"; tableId: string }
  | { type: "reset" }

export function initialGraphState(): LineageGraphState {
  return {
    anchorId: null,
    nodes: [],
    edges: [],
    expanded: new Set(),
    baseNodeIds: new Set(),
    children: {},
    columnsByTable: {},
    columnEdgesByTable: {},
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
        columnsByTable: {},
        columnEdgesByTable: {},
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

    case "expandColumns": {
      const columnsByTable = { ...state.columnsByTable, [action.tableId]: action.columns }
      const columnEdgesByTable = { ...state.columnEdgesByTable, [action.tableId]: action.columnEdges ?? [] }
      return { ...state, columnsByTable, columnEdgesByTable }
    }

    case "collapseColumns": {
      const columnsByTable = { ...state.columnsByTable }
      delete columnsByTable[action.tableId]
      const columnEdgesByTable = { ...state.columnEdgesByTable }
      delete columnEdgesByTable[action.tableId]
      return { ...state, columnsByTable, columnEdgesByTable }
    }

    case "reset":
      return initialGraphState()

    default:
      return state
  }
}
