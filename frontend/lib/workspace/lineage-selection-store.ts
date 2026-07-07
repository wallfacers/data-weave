/**
 * 血缘选中态 store（052，research D4）。
 *
 * 持有当前选中的节点 / 边 / 面板 Tab。不复用 useNodeDetailStore（后者硬编码请求
 * 工作流节点详情接口，与血缘领域无关）。面板内容由 panelTab 决定：node | edge | impact。
 */
import { create } from "zustand"
import type { FlowEdgeView, GraphNodeView, ImpactResult } from "@/lib/lineage-api"

export type LineagePanelTab = "node" | "edge" | "impact"

export interface LineageSelectionState {
  selectedNode: GraphNodeView | null
  selectedEdge: FlowEdgeView | null
  /** 影响分析结果（impact Tab 用；由视图写入）。 */
  impact: ImpactResult | null
  panelTab: LineagePanelTab
  /** 嵌入面板是否打开（选中任意对象或显式打开 impact Tab 时为 true）。 */
  panelOpen: boolean

  selectNode: (node: GraphNodeView | null) => void
  selectEdge: (edge: FlowEdgeView | null) => void
  setImpact: (impact: ImpactResult | null) => void
  /** 切到影响 Tab（需先有锚点/impact）。 */
  showImpact: () => void
  setPanelTab: (tab: LineagePanelTab) => void
  closePanel: () => void
}

export const useLineageSelection = create<LineageSelectionState>((set) => ({
  selectedNode: null,
  selectedEdge: null,
  impact: null,
  panelTab: "node",
  panelOpen: false,

  selectNode: (node) =>
    set({
      selectedNode: node,
      selectedEdge: null,
      panelTab: "node",
      panelOpen: node != null,
    }),

  selectEdge: (edge) =>
    set({
      selectedEdge: edge,
      panelTab: "edge",
      panelOpen: edge != null,
    }),

  setImpact: (impact) => set({ impact }),

  showImpact: () => set({ panelTab: "impact", panelOpen: true }),

  setPanelTab: (tab) => set({ panelTab: tab, panelOpen: true }),

  closePanel: () =>
    set({
      selectedNode: null,
      selectedEdge: null,
      impact: null,
      panelTab: "node",
      panelOpen: false,
    }),
}))
