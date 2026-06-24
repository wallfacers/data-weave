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
