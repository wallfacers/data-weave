"use client"

/**
 * 血缘节点动作上下文 —— 画布上方提供，供 LineageNode 内的展开/选中按钮回调。
 *
 * 模式同 workflow-canvas 的 NodeActionsContext：provider 包裹画布（在 ReactFlowProvider
 * 之外、FlowCanvasWithPanel 之外亦可，React context 沿组件树流入 ReactFlow 渲染的节点）。
 * 未提供时（如纯只读嵌入）节点不渲染动作按钮。
 */
import { createContext, useContext } from "react"

export interface LineageNodeActions {
  /** 点击节点体（非展开按钮）→ 选中。 */
  onSelectNode?: (nodeId: string) => void
  /** 表节点「展开/收起列」按钮 → 增量加载或收起列清单。 */
  onToggleExpand?: (nodeId: string) => void
}

export const LineageNodeActionsContext = createContext<LineageNodeActions | null>(null)

export function useLineageNodeActions(): LineageNodeActions | null {
  return useContext(LineageNodeActionsContext)
}
