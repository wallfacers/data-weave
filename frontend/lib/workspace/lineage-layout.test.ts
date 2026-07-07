import { describe, expect, it } from "vitest"
import { lineageToFlow, edgeKey, isInferredEdge } from "@/lib/workspace/lineage-layout"
import type { FlowEdgeView, GraphNodeView } from "@/lib/lineage-api"

function table(id: string, name = id, layer = "DWD"): GraphNodeView {
  return { id, type: "TABLE", name, layer }
}

function edge(from: string, to: string, over: Partial<FlowEdgeView> = {}): FlowEdgeView {
  return { from, to, granularity: "TABLE", ...over }
}

describe("lineageToFlow", () => {
  it("LR 分层：a→b→c 链上游在左、下游在右（x 严格递增）", () => {
    const graph = {
      nodes: [table("a"), table("b"), table("c")],
      edges: [edge("a", "b"), edge("b", "c")],
    }
    const { nodes, edges } = lineageToFlow(graph)
    const pos = (id: string) => nodes.find((n) => n.id === id)!.position
    expect(pos("a").x).toBeLessThan(pos("b").x)
    expect(pos("b").x).toBeLessThan(pos("c").x)
    expect(edges).toHaveLength(2)
  })

  it("同源多下游落在同一 rank（x 相近），锚点居左", () => {
    const graph = {
      nodes: [table("a", "ods_a", "ODS"), table("b"), table("c")],
      edges: [edge("a", "b"), edge("a", "c")],
    }
    const { nodes } = lineageToFlow(graph, { anchorId: "a" })
    const pos = (id: string) => nodes.find((n) => n.id === id)!.position
    expect(pos("a").x).toBeLessThan(pos("b").x)
    // b、c 同 rank → x 基本相等（dagre 同层）
    expect(Math.abs(pos("b").x - pos("c").x)).toBeLessThan(1)
    // 锚点 data 标记
    expect(nodes.find((n) => n.id === "a")!.data.isAnchor).toBe(true)
  })

  it("丢弃悬挂边（to 不在节点集），保持边闭合", () => {
    const graph = {
      nodes: [table("a"), table("b")],
      edges: [edge("a", "b"), edge("a", "ghost")],
    }
    const { nodes, edges } = lineageToFlow(graph)
    expect(nodes).toHaveLength(2)
    expect(edges).toHaveLength(1)
    expect(edges[0].target).toBe("b")
  })

  it("边 id 唯一；推断边虚线、确认边 success 色", () => {
    const graph = {
      nodes: [table("a"), table("b"), table("c"), table("d")],
      edges: [
        edge("a", "b", { confidence: "CONFIRMED" }),
        edge("b", "c", { source: "SCRIPT_INFERRED" }),
        edge("c", "d"),
      ],
    }
    const { edges } = lineageToFlow(graph)
    const ids = edges.map((e) => e.id)
    expect(new Set(ids).size).toBe(ids.length)
    const confirmed = edges.find((e) => e.source === "a" && e.target === "b")!
    const inferred = edges.find((e) => e.source === "b" && e.target === "c")!
    expect(confirmed.style?.stroke).toBe("var(--color-success)")
    expect(inferred.style?.strokeDasharray).toBe("5 3")
  })

  it("选中节点 → 其邻接边高亮（primary 色、加粗、animated）", () => {
    const graph = {
      nodes: [table("a"), table("b"), table("c")],
      edges: [edge("a", "b"), edge("b", "c")],
    }
    const { edges } = lineageToFlow(graph, { selectedNodeId: "b" })
    const ab = edges.find((e) => e.source === "a" && e.target === "b")!
    const bc = edges.find((e) => e.source === "b" && e.target === "c")!
    expect(ab.style?.stroke).toBe("var(--color-primary)")
    expect(bc.style?.stroke).toBe("var(--color-primary)")
    expect(ab.animated).toBe(true)
  })

  it("孤立锚点（无任何边）仍可落点不报错", () => {
    const { nodes } = lineageToFlow({ nodes: [table("solo")], edges: [] }, { anchorId: "solo" })
    expect(nodes).toHaveLength(1)
    expect(nodes[0].data.isAnchor).toBe(true)
  })
})

describe("edge helpers", () => {
  it("edgeKey 忽略 taskDefId，from→to 即键", () => {
    expect(edgeKey({ from: "a", to: "b" })).toBe("a→b")
  })
  it("isInferredEdge：SCRIPT_INFERRED / SCRIPT_MODEL / UNVERIFIED 为推断", () => {
    expect(isInferredEdge(edge("a", "b", { source: "SCRIPT_INFERRED" }))).toBe(true)
    expect(isInferredEdge(edge("a", "b", { source: "SCRIPT_MODEL" }))).toBe(true)
    expect(isInferredEdge(edge("a", "b", { confidence: "UNVERIFIED" }))).toBe(true)
    expect(isInferredEdge(edge("a", "b", { confidence: "CONFIRMED" }))).toBe(false)
  })
})
