/**
 * 工作流 DAG 自动布局 —— dagre rankdir=LR（从左到右、从上到下对齐）。
 *
 * ReactFlow 本身不提供布局算法，官方推荐 dagre / ELK。
 * 项目已依赖 @dagrejs/dagre（血缘图用），此处复用。
 */

import dagre from "@dagrejs/dagre"

/** 节点类型 → dagre 占位尺寸。与 task-node.tsx / virtual-node.tsx 渲染尺寸对齐。 */
const NODE_SIZE: Record<string, { width: number; height: number }> = {
  task: { width: 200, height: 60 },
  virtual: { width: 160, height: 40 },
}

const DEFAULT_SIZE = { width: 200, height: 60 }

/** 自动布局的入参节点（最少字段集）。 */
export interface LayoutNode {
  nodeKey: string
  nodeType?: string
}

/** 自动布局的入参边。 */
export interface LayoutEdge {
  fromNodeKey: string
  toNodeKey: string
}

/** 布局结果：每个节点的 { x, y }（ReactFlow 左上角坐标）。 */
export interface LayoutResult {
  positions: Map<string, { x: number; y: number }>
}

/**
 * 对工作流 DAG 节点做 dagre 布局。
 *
 * @param nodes - 所有节点
 * @param edges - 所有边
 * @param opts.direction - 布局方向，默认 "LR"（左→右）
 * @returns 每个 nodeKey 对应的布局坐标
 */
export function autoLayoutWorkflow(
  nodes: LayoutNode[],
  edges: LayoutEdge[],
  opts?: { direction?: "LR" | "TB" },
): LayoutResult {
  const dir = opts?.direction ?? "LR"

  const g = new dagre.graphlib.Graph()
  g.setGraph({
    rankdir: dir,
    nodesep: 40,   // 同层节点水平间距（LR 下为垂直间距）
    ranksep: 120,  // 层级间距（LR 下为水平间距）
    marginx: 20,
    marginy: 20,
  })
  g.setDefaultEdgeLabel(() => ({}))

  // 添加节点
  for (const n of nodes) {
    if (!g.hasNode(n.nodeKey)) {
      const size = NODE_SIZE[n.nodeType ?? "task"] ?? DEFAULT_SIZE
      g.setNode(n.nodeKey, { width: size.width, height: size.height })
    }
  }

  // 添加边
  for (const e of edges) {
    if (g.hasNode(e.fromNodeKey) && g.hasNode(e.toNodeKey)) {
      g.setEdge(e.fromNodeKey, e.toNodeKey)
    }
  }

  // 孤立节点也要定位
  if (g.nodeCount() > 0) {
    dagre.layout(g)
  }

  // 提取坐标（dagre 返回中心点 → ReactFlow 左上角）
  const positions = new Map<string, { x: number; y: number }>()
  for (const n of nodes) {
    const pos = g.node(n.nodeKey)
    if (pos) {
      const size = NODE_SIZE[n.nodeType ?? "task"] ?? DEFAULT_SIZE
      positions.set(n.nodeKey, {
        x: pos.x - size.width / 2,
        y: pos.y - size.height / 2,
      })
    }
  }

  return { positions }
}
