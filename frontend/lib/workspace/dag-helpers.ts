/**
 * DAG 编辑态纯函数（从 workflow-canvas-view 抽出，便于 vitest 单测，无 ReactFlow/DOM 依赖）。
 */

/**
 * 在现有边集上加入 source→target 是否成环：从 target 出发能否回到 source。
 * 自指（source===target）视为成环。用于画布连边前置校验。
 */
export function wouldCreateCycle(
  edges: { source: string; target: string }[],
  source: string,
  target: string,
): boolean {
  if (source === target) return true
  const adj = new Map<string, string[]>()
  for (const e of edges) {
    if (!adj.has(e.source)) adj.set(e.source, [])
    adj.get(e.source)!.push(e.target)
  }
  const stack = [target]
  const seen = new Set<string>()
  while (stack.length) {
    const cur = stack.pop()!
    if (cur === source) return true
    if (seen.has(cur)) continue
    seen.add(cur)
    for (const nxt of adj.get(cur) ?? []) stack.push(nxt)
  }
  return false
}

/**
 * 将后端 DagView 映射为 ReactFlow nodes + edges（供 canvas 与 dag-viewer-dialog 共用）。
 */
export function dagViewToFlow(
  dag: { nodes: { nodeKey: string; nodeType: string; taskId: number | null;
    taskVersionNo?: number | null; name: string | null; posX: number | null; posY: number | null; taskStatus?: string | null }[];
    edges: { fromNodeKey: string; toNodeKey: string; strength?: string }[] },
): { nodes: import("@/components/workspace/nodes/canvas-node-types").CanvasNode[]; edges: import("@xyflow/react").Edge[] } {
  const nodes: import("@/components/workspace/nodes/canvas-node-types").CanvasNode[] = dag.nodes.map((n) => ({
    id: n.nodeKey,
    type: n.nodeType === "VIRTUAL" ? "virtual" : "task",
    position: { x: n.posX ?? 0, y: n.posY ?? 0 },
    data: { nodeType: (n.nodeType as "TASK" | "VIRTUAL"), taskId: n.taskId, label: n.name ?? "", taskStatus: n.taskStatus ?? null, taskVersionNo: n.taskVersionNo ?? null },
  }))
  const edges: import("@xyflow/react").Edge[] = dag.edges.map((e) => {
    const strength = e.strength ?? "STRONG"
    return {
      id: `${e.fromNodeKey}->${e.toNodeKey}`,
      source: e.fromNodeKey,
      target: e.toNodeKey,
      data: { strength },
      ...(strength === "WEAK" ? { animated: true, style: { strokeDasharray: "6 4" } } : {}),
    }
  })
  return { nodes, edges }
}

/**
 * 将 InstanceDagView（实例级 DAG，含运行时状态）转换为 ReactFlow nodes/edges。
 * 与 dagViewToFlow 结构一致，额外叠加 runState / highlight 等运行时信息。
 */
export function instanceDagViewToFlow(
  dag: import("@/lib/types").InstanceDagView,
  highlightNodeKey?: string | null,
): { nodes: import("@/components/workspace/nodes/canvas-node-types").CanvasNode[]; edges: import("@xyflow/react").Edge[] } {
  const highlighted = highlightNodeKey ?? null
  const nodes: import("@/components/workspace/nodes/canvas-node-types").CanvasNode[] = dag.nodes.map((n) => ({
    id: n.nodeKey,
    type: n.nodeType === "VIRTUAL" ? "virtual" : "task",
    position: { x: n.posX ?? 0, y: n.posY ?? 0 },
    data: {
      nodeType: (n.nodeType as "TASK" | "VIRTUAL") || "TASK",
      taskId: n.taskId,
      label: n.taskName ?? "",
      runState: n.state,       // 实例运行时状态叠加
      taskInstanceId: n.taskInstanceId,
      attempt: n.attempt,
      startedAt: n.startedAt,
      finishedAt: n.finishedAt,
      durationMs: n.durationMs,
    },
    style: highlighted && n.nodeKey === highlighted
      ? { border: "2px solid hsl(var(--primary))", borderRadius: "var(--radius-md)" }
      : undefined,
  }))
  const edges: import("@xyflow/react").Edge[] = dag.edges.map((e) => ({
    id: `${e.fromNodeKey}->${e.toNodeKey}`,
    source: e.fromNodeKey,
    target: e.toNodeKey,
    data: { strength: e.strength ?? "STRONG" },
    ...(e.strength === "WEAK" ? { animated: true, style: { strokeDasharray: "6 4" } } : {}),
  }))
  return { nodes, edges }
}
