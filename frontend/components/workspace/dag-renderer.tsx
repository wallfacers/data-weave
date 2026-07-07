"use client"

/**
 * 工作流 DAG 只读渲染器（开发画布与运维弹框共用）。
 *
 * 从 workflow-canvas-view 抽出 ReactFlow 核心渲染逻辑，保证两处展示 100% 一致。
 * 只读模式下关闭所有编辑交互（拖拽/连线/删除/右键菜单）。
 */
import type { ReactNode, MouseEvent as ReactMouseEvent } from "react"
import {
  ReactFlow,
  Background,
  Controls,
  MiniMap,
  type Node,
  type Edge,
  type NodeTypes,
} from "@xyflow/react"
import "@xyflow/react/dist/style.css"

export interface DagRendererProps {
  /** ReactFlow instance id（同一页面多个实例时需唯一）。 */
  rfId: string
  nodes: Node[]
  edges: Edge[]
  nodeTypes: NodeTypes
  readOnly?: boolean
  /** 边右键菜单（仅编辑态） */
  onEdgeContextMenu?: (e: ReactMouseEvent, edge: Edge) => void
  onPaneClick?: () => void
  /** 节点点击回调（readOnly 模式下仍触发，供 Ops DAG 弹框展示节点详情）。 */
  onNodeClick?: (event: ReactMouseEvent, node: Node) => void
  /** 节点右键菜单回调（readOnly 模式下仍触发）。 */
  onNodeContextMenu?: (event: ReactMouseEvent, node: Node) => void
  /** 边点击（052 血缘选中边用，readOnly 下仍触发）。 */
  onEdgeClick?: (event: ReactMouseEvent, edge: Edge) => void
  onMoveStart?: () => void
  /** 编辑态回调 */
  onNodesChange?: (changes: any) => void
  onEdgesChange?: (changes: any) => void
  onConnect?: (connection: any) => void
  /** 是否显示 MiniMap（编辑态 true，弹框 false）。 */
  showMiniMap?: boolean
  /** 额外子元素（如右键菜单浮层）。 */
  children?: ReactNode
}

export function DagRenderer({
  rfId,
  nodes,
  edges,
  nodeTypes,
  readOnly = false,
  onEdgeContextMenu,
  onPaneClick,
  onNodeClick,
  onNodeContextMenu,
  onEdgeClick,
  onMoveStart,
  onNodesChange,
  onEdgesChange,
  onConnect,
  showMiniMap = false,
  children,
}: DagRendererProps) {
  return (
    <ReactFlow
      id={rfId}
      nodes={nodes}
      edges={edges}
      nodeTypes={nodeTypes}
      fitView
      fitViewOptions={{ padding: 0.2 }}
      proOptions={{ hideAttribution: true }}
      // 编辑交互 —— 只读时全部关闭
      nodesDraggable={!readOnly}
      nodesConnectable={!readOnly}
      deleteKeyCode={readOnly ? undefined : ["Backspace", "Delete"]}
      panActivationKeyCode={null}
      // 编辑回调
      onNodesChange={readOnly ? undefined : onNodesChange}
      onEdgesChange={readOnly ? undefined : onEdgesChange}
      onConnect={readOnly ? undefined : onConnect}
      onEdgeContextMenu={readOnly ? undefined : onEdgeContextMenu}
      onEdgeClick={onEdgeClick}
      // pane/节点点击 —— 只读/编辑模式均触发（只读供 Ops DAG 弹框的节点详情面板）
      onPaneClick={onPaneClick}
      onNodeClick={onNodeClick}
      onNodeContextMenu={onNodeContextMenu}
      onMoveStart={readOnly ? undefined : onMoveStart}
    >
      <Background />
      <Controls showInteractive={!readOnly} />
      {showMiniMap && <MiniMap pannable zoomable />}
      {children}
    </ReactFlow>
  )
}
