import { describe, expect, it } from "vitest"
import {
  lineageGraphReducer,
  initialGraphState,
} from "@/lib/workspace/lineage-graph"
import type { FlowEdgeView, GraphNodeView } from "@/lib/lineage-api"

const tbl = (id: string): GraphNodeView => ({ id, type: "TABLE", name: id })
const e = (from: string, to: string): FlowEdgeView => ({ from, to, granularity: "TABLE" })

describe("lineageGraphReducer", () => {
  it("load 替换全图并建立基集", () => {
    const s = lineageGraphReducer(initialGraphState(), {
      type: "load",
      anchorId: "a",
      nodes: [tbl("a"), tbl("b")],
      edges: [e("a", "b")],
      truncated: false,
    })
    expect(s.anchorId).toBe("a")
    expect(s.nodes).toHaveLength(2)
    expect(s.edges).toHaveLength(1)
    expect(s.baseNodeIds.has("a")).toBe(true)
    expect(s.expanded.size).toBe(0)
  })

  it("expand 追加不重复节点/边，记录子节点", () => {
    let s = lineageGraphReducer(initialGraphState(), {
      type: "load",
      anchorId: "a",
      nodes: [tbl("a"), tbl("b")],
      edges: [e("a", "b")],
      truncated: false,
    })
    s = lineageGraphReducer(s, {
      type: "expand",
      nodeId: "b",
      nodes: [tbl("c"), tbl("d")],
      edges: [e("b", "c"), e("b", "d")],
    })
    expect(s.nodes).toHaveLength(4)
    expect(s.edges).toHaveLength(3)
    expect(s.expanded.has("b")).toBe(true)
    expect(s.children["b"]).toEqual(["c", "d"])
  })

  it("expand 已出现的节点不重复加（dedup id）", () => {
    let s = lineageGraphReducer(initialGraphState(), {
      type: "load",
      anchorId: "a",
      nodes: [tbl("a"), tbl("b"), tbl("c")],
      edges: [e("a", "b"), e("b", "c")],
      truncated: false,
    })
    s = lineageGraphReducer(s, {
      type: "expand",
      nodeId: "c",
      nodes: [tbl("b"), tbl("d")], // b 已存在，d 新
      edges: [e("c", "d"), e("b", "c")], // b→c 重复
    })
    expect(s.nodes).toHaveLength(4) // a,b,c + d
    expect(s.edges).toHaveLength(3) // a→b, b→c, c→d
    expect(s.children["c"]).toEqual(["d"])
  })

  it("collapse 移除独占子节点（非基图/非其他展开/非锚点），保留共享节点", () => {
    let s = lineageGraphReducer(initialGraphState(), {
      type: "load",
      anchorId: "a",
      nodes: [tbl("a"), tbl("b")],
      edges: [e("a", "b")],
      truncated: false,
    })
    // expand b → {c, d}
    s = lineageGraphReducer(s, {
      type: "expand",
      nodeId: "b",
      nodes: [tbl("c"), tbl("d")],
      edges: [e("b", "c"), e("b", "d")],
    })
    // expand c → {e}
    s = lineageGraphReducer(s, {
      type: "expand",
      nodeId: "c",
      nodes: [tbl("e")],
      edges: [e("c", "e")],
    })
    expect(s.nodes).toHaveLength(5) // a,b,c,d,e

    // collapse c → e 移除（独占），但 d 作为 b 的子节点受保护
    s = lineageGraphReducer(s, { type: "collapse", nodeId: "c" })
    expect(s.nodes.map((n) => n.id).sort()).toEqual(["a", "b", "c", "d"])
    expect(s.edges.map((e) => e.from + "→" + e.to).sort()).toEqual([
      "a→b", "b→c", "b→d",
    ])
    expect(s.expanded.has("b")).toBe(true)
    expect(s.expanded.has("c")).toBe(false)
    // b 的子节点 d 仍保留
    expect(s.children["b"]).toEqual(["c", "d"])
    expect(s.children["c"]).toBeUndefined()
  })

  it("collapse 锚节点时不误删基图节点", () => {
    let s = lineageGraphReducer(initialGraphState(), {
      type: "load",
      anchorId: "a",
      nodes: [tbl("a"), tbl("b")],
      edges: [e("a", "b")],
      truncated: false,
    })
    s = lineageGraphReducer(s, {
      type: "expand",
      nodeId: "a",
      nodes: [tbl("x")],
      edges: [e("a", "x")],
    })
    s = lineageGraphReducer(s, { type: "collapse", nodeId: "a" })
    // b 是基图节点，不删；x 是 a 引入的独占节点，删
    expect(s.nodes.map((n) => n.id).sort()).toEqual(["a", "b"])
  })

  it("reset 清空全态", () => {
    let s = lineageGraphReducer(initialGraphState(), {
      type: "load",
      anchorId: "x",
      nodes: [tbl("x"), tbl("y")],
      edges: [e("x", "y")],
      truncated: false,
    })
    s = lineageGraphReducer(s, { type: "reset" })
    expect(s.anchorId).toBeNull()
    expect(s.nodes).toHaveLength(0)
    expect(s.edges).toHaveLength(0)
  })
})
