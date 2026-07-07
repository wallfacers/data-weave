import { beforeEach, describe, expect, it } from "vitest"
import { useLineageSelection } from "@/lib/workspace/lineage-selection-store"
import type { FlowEdgeView, GraphNodeView, ImpactResult } from "@/lib/lineage-api"

const node = (id: string): GraphNodeView => ({ id, type: "TABLE", name: id })
const edge = (from: string, to: string): FlowEdgeView => ({ from, to, granularity: "TABLE" })

beforeEach(() => useLineageSelection.getState().closePanel())

describe("lineage-selection-store", () => {
  it("初始为空、面板关闭、Tab=node", () => {
    const s = useLineageSelection.getState()
    expect(s.selectedNode).toBeNull()
    expect(s.selectedEdge).toBeNull()
    expect(s.impact).toBeNull()
    expect(s.panelTab).toBe("node")
    expect(s.panelOpen).toBe(false)
  })

  it("selectNode 设节点、清边、切 Tab=node、开面板", () => {
    useLineageSelection.getState().selectEdge(edge("a", "b"))
    useLineageSelection.getState().selectNode(node("x"))
    const s = useLineageSelection.getState()
    expect(s.selectedNode?.id).toBe("x")
    expect(s.selectedEdge).toBeNull()
    expect(s.panelTab).toBe("node")
    expect(s.panelOpen).toBe(true)
  })

  it("selectEdge 切 Tab=edge、保留节点上下文", () => {
    useLineageSelection.getState().selectNode(node("x"))
    useLineageSelection.getState().selectEdge(edge("a", "b"))
    const s = useLineageSelection.getState()
    expect(s.selectedEdge?.from).toBe("a")
    expect(s.panelTab).toBe("edge")
    expect(s.selectedNode?.id).toBe("x") // 节点上下文保留（供影响 Tab 复用）
  })

  it("selectNode(null) 关闭面板", () => {
    useLineageSelection.getState().selectNode(node("x"))
    useLineageSelection.getState().selectNode(null)
    expect(useLineageSelection.getState().panelOpen).toBe(false)
  })

  it("showImpact 切 Tab=impact 并开面板；setImpact 写入结果", () => {
    const impact: ImpactResult = {
      root: node("x"),
      downstream: [node("y")],
      edges: [],
      nodeCount: 1,
      reachableTotal: 1,
      totalIsLowerBound: false,
      truncated: false,
    }
    useLineageSelection.getState().setImpact(impact)
    useLineageSelection.getState().showImpact()
    const s = useLineageSelection.getState()
    expect(s.panelTab).toBe("impact")
    expect(s.panelOpen).toBe(true)
    expect(s.impact?.reachableTotal).toBe(1)
  })

  it("closePanel 清空一切并复位", () => {
    useLineageSelection.getState().selectNode(node("x"))
    useLineageSelection.getState().selectEdge(edge("a", "b"))
    useLineageSelection.getState().closePanel()
    const s = useLineageSelection.getState()
    expect(s.selectedNode).toBeNull()
    expect(s.selectedEdge).toBeNull()
    expect(s.impact).toBeNull()
    expect(s.panelOpen).toBe(false)
    expect(s.panelTab).toBe("node")
  })
})
