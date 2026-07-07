import { describe, expect, it } from "vitest"
import { lineageToFlow, edgeKey, isInferredEdge } from "@/lib/workspace/lineage-layout"
import { datasourceColor } from "@/lib/workspace/lineage-datasource-style"
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

function tableWithDs(id: string, dsId: string): GraphNodeView {
  return { id, type: "TABLE", name: id, attrs: { datasourceId: dsId } }
}

describe("054 跨源边判定（FR-008/013）", () => {
  it("两端数据源不同 → cross，描边 warning 色", () => {
    const graph = {
      nodes: [tableWithDs("a", "ds-1"), tableWithDs("c", "ds-2")],
      edges: [edge("a", "c")],
    }
    const { edges } = lineageToFlow(graph)
    const ac = edges.find((e) => e.source === "a" && e.target === "c")!
    expect(ac.style?.stroke).toBe("var(--color-warning)")
  })

  it("两端同源 → intra，非 warning 色", () => {
    const graph = {
      nodes: [tableWithDs("a", "ds-1"), tableWithDs("b", "ds-1")],
      edges: [edge("a", "b")],
    }
    const { edges } = lineageToFlow(graph)
    const ab = edges.find((e) => e.source === "a" && e.target === "b")!
    expect(ab.style?.stroke).not.toBe("var(--color-warning)")
  })

  it("任一端无数据源（metric/孤儿）→ unknown，非 warning（不误判跨源）", () => {
    const metric: GraphNodeView = { id: "m", type: "METRIC", name: "m" }
    const graph = {
      nodes: [tableWithDs("a", "ds-1"), metric],
      edges: [edge("a", "m")],
    }
    const { edges } = lineageToFlow(graph)
    const am = edges.find((e) => e.source === "a" && e.target === "m")!
    expect(am.style?.stroke).not.toBe("var(--color-warning)")
  })
})

describe("054 列级连线（FR-012/013）", () => {
  const colEdge = { from: "colA1", to: "colB1", granularity: "COLUMN" } as FlowEdgeView

  it("两表展开且列可见 → 列级边连到具体列行（sourceHandle/targetHandle=列 id）", () => {
    const graph = {
      nodes: [tableWithDs("tA", "ds-1"), tableWithDs("tB", "ds-1")],
      edges: [edge("tA", "tB")],
    }
    const { edges } = lineageToFlow(graph, {
      columnsByTable: {
        tA: [{ id: "colA1", name: "x" }],
        tB: [{ id: "colB1", name: "u" }],
      },
      columnEdgesByTable: { tA: [colEdge] },
    })
    const ce = edges.find((e) => e.sourceHandle === "colA1" && e.targetHandle === "colB1")
    expect(ce).toBeTruthy()
    expect(ce!.source).toBe("tA")
    expect(ce!.target).toBe("tB")
  })

  it("一端列不可见（表未展开）→ 不画悬挂列级连线", () => {
    const graph = {
      nodes: [tableWithDs("tA", "ds-1"), tableWithDs("tB", "ds-1")],
      edges: [edge("tA", "tB")],
    }
    const { edges } = lineageToFlow(graph, {
      columnsByTable: { tA: [{ id: "colA1", name: "x" }] },
      columnEdgesByTable: { tA: [colEdge] },
    })
    expect(edges.find((e) => e.sourceHandle === "colA1")).toBeFalsy()
  })

  it("列级跨库映射复用跨源 warning 色", () => {
    const graph = {
      nodes: [tableWithDs("tA", "ds-1"), tableWithDs("tB", "ds-2")],
      edges: [edge("tA", "tB")],
    }
    const { edges } = lineageToFlow(graph, {
      columnsByTable: {
        tA: [{ id: "colA1", name: "x" }],
        tB: [{ id: "colB1", name: "u" }],
      },
      columnEdgesByTable: { tA: [colEdge] },
    })
    const ce = edges.find((e) => e.sourceHandle === "colA1" && e.targetHandle === "colB1")!
    expect(ce.style?.stroke).toBe("var(--color-warning)")
  })
})

describe("054 datasourceColor 确定性（FR-011，详见 lineage-datasource-style.test）", () => {
  it("同 id 同色、返回 chart token、空 → 中性", () => {
    expect(datasourceColor("ds-1")).toBe(datasourceColor("ds-1"))
    expect(datasourceColor("ds-1")).toMatch(/^var\(--color-chart-[1-5]\)$/)
    expect(datasourceColor(undefined)).toBe("var(--color-muted-foreground)")
  })
})
