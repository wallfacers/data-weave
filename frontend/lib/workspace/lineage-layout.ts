/**
 * 血缘分层布局 —— dagre rankdir=LR 纯函数（research D2）。
 *
 * 输入 LineageGraph（节点+边）→ 输出 ReactFlow { nodes, edges }。
 * 上游自动落左、下游落右（LR 分层），同 rank 内 dagre 减少交叉。
 * 边样式在此编码：置信度/来源驱动 实线/虚线/色相（FR-016/018）；选中/影响/路径高亮。
 *
 * 纯函数、无 React 依赖，可直接在 node 测试环境跑（vitest）。
 */
import dagre from "@dagrejs/dagre"
import type { Edge } from "@xyflow/react"
import type {
  FlowEdgeView,
  GraphNodeView,
  NodeType,
} from "@/lib/lineage-api"
import { readNodeAttrs } from "@/lib/lineage-api"
import type { LineageNode, LineageNodeData } from "@/components/workspace/nodes/lineage-node-types"

/** 边唯一 key（供高亮集去重；忽略 taskDefId 以便路径/影响匹配聚合）。 */
export function edgeKey(e: { from: string; to: string }): string {
  return `${e.from}→${e.to}`
}

/** 推断边（规则/模型/未验证）→ 虚线区分。 */
export function isInferredEdge(edge: FlowEdgeView): boolean {
  return (
    edge.source === "SCRIPT_INFERRED" ||
    edge.source === "SCRIPT_MODEL" ||
    edge.confidence === "UNVERIFIED"
  )
}

/** 节点类型 → dagre 占位尺寸（与 lineage-node.tsx 渲染尺寸对齐，留 nodesep 余量）。 */
function nodeSize(type: NodeType): { width: number; height: number } {
  switch (type) {
    case "COLUMN":
      return { width: 176, height: 40 }
    case "DATASOURCE":
    case "METRIC":
      return { width: 208, height: 48 }
    default:
      // TABLE 带元信息行略高
      return { width: 208, height: 62 }
  }
}

export interface LineageLayoutOptions {
  /** 当前锚点（居中标记）。 */
  anchorId?: string
  /** 已展开列的表节点。 */
  expandedNodeIds?: Set<string>
  /** 影响/路径高亮集内的节点 id。 */
  impactedNodeIds?: Set<string>
  /** 当前选中节点（高亮其直接连边）。 */
  selectedNodeId?: string | null
  /** 高亮边 key 集合（影响 edges / 路径 edges）。 */
  highlightEdgeKeys?: Set<string>
  /** 是否对非相关节点/边置暗（选中/影响/路径模式）。 */
  dimUnrelated?: boolean
}

export interface LineageLayoutResult {
  nodes: LineageNode[]
  edges: Edge[]
}

/** 把 LineageGraph 经 dagre LR 布局为 ReactFlow 节点+边。 */
export function lineageToFlow(
  graph: { nodes: GraphNodeView[]; edges: FlowEdgeView[] },
  opts: LineageLayoutOptions = {},
): LineageLayoutResult {
  const {
    anchorId,
    expandedNodeIds,
    impactedNodeIds,
    selectedNodeId,
    highlightEdgeKeys,
    dimUnrelated,
  } = opts

  const nodeById = new Map<string, GraphNodeView>()
  graph.nodes.forEach((n) => nodeById.set(n.id, n))

  // 相关节点集：锚点 + 影响/路径节点 + 选中节点（dimUnrelated 时据此置暗其余）
  const relevantNodeIds = new Set<string>()
  if (anchorId) relevantNodeIds.add(anchorId)
  impactedNodeIds?.forEach((id) => relevantNodeIds.add(id))
  if (selectedNodeId) relevantNodeIds.add(selectedNodeId)
  highlightEdgeKeys?.forEach((k) => {
    const [from, to] = k.split("→")
    if (from) relevantNodeIds.add(from)
    if (to) relevantNodeIds.add(to)
  })

  // ── dagre 布局 ──
  const g = new dagre.graphlib.Graph()
  g.setGraph({ rankdir: "LR", nodesep: 28, ranksep: 96, marginx: 16, marginy: 16 })
  g.setDefaultEdgeLabel(() => ({}))

  graph.nodes.forEach((n) => {
    if (!g.hasNode(n.id)) {
      const { width, height } = nodeSize(n.type)
      g.setNode(n.id, { width, height })
    }
  })
  graph.edges.forEach((e) => {
    if (g.hasNode(e.from) && g.hasNode(e.to) && !g.hasEdge(e.from, e.to)) {
      g.setEdge(e.from, e.to)
    }
  })
  // 孤立锚点（无任何边）也要落点
  if (g.nodeCount() > 0) dagre.layout(g)

  // ── ReactFlow 节点 ──
  const nodes: LineageNode[] = graph.nodes.map((n) => {
    const pos = g.node(n.id)
    const attrs = readNodeAttrs(n)
    const isExpanded = expandedNodeIds?.has(n.id) ?? false
    const data: LineageNodeData = {
      nodeType: n.type,
      name: n.name,
      layer: attrs.layer ?? n.layer,
      granularity: n.granularity,
      lastSyncDate: attrs.lastSyncDate,
      syncedRowsToday: attrs.syncedRowsToday,
      producers: attrs.producers,
      columnCount: typeof n.attrs?.columnCount === "number" ? (n.attrs.columnCount as number) : undefined,
      isAnchor: n.id === anchorId,
      isImpacted: impactedNodeIds?.has(n.id) ?? false,
      expanded: isExpanded,
      dimmed: dimUnrelated ? !relevantNodeIds.has(n.id) : false,
    }
    // dagre 返回中心点，ReactFlow position 用左上角 → 各减半宽高
    const w = pos?.width ?? 0
    const h = pos?.height ?? 0
    return {
      id: n.id,
      type: n.type.toLowerCase(),
      position: { x: (pos?.x ?? 0) - w / 2, y: (pos?.y ?? 0) - h / 2 },
      data,
    }
  })

  // ── ReactFlow 边（样式编码 + 高亮）──
  const seen = new Set<string>()
  const edges: Edge[] = []
  graph.edges.forEach((e, idx) => {
    if (!nodeById.has(e.from) || !nodeById.has(e.to)) return // 闭合：丢弃悬挂边
    const key = edgeKey(e)
    const id = seen.has(key) ? `${key}#${idx}` : key
    seen.add(key)

    const inHighlight = highlightEdgeKeys?.has(key) ?? false
    const adjacentToSelected =
      !!selectedNodeId && (e.from === selectedNodeId || e.to === selectedNodeId)
    const highlighted = inHighlight || adjacentToSelected

    const inferred = isInferredEdge(e)
    const confirmed = e.humanState === "CONFIRMED" || e.confidence === "CONFIRMED"

    const stroke = highlighted
      ? "var(--color-primary)"
      : confirmed
        ? "var(--color-success)"
        : "var(--color-border)"
    const strokeWidth = highlighted ? 2.5 : confirmed ? 1.75 : 1.25
    const strokeDasharray = inferred ? "5 3" : undefined
    const opacity = dimUnrelated && !highlighted ? 0.25 : highlighted ? 1 : 0.7

    edges.push({
      id,
      source: e.from,
      target: e.to,
      style: { stroke, strokeWidth, strokeDasharray, opacity },
      animated: highlighted,
      data: e as unknown as Record<string, unknown>,
    })
  })

  return { nodes, edges }
}
