"use client"

/**
 * 工作流 DAG 只读渲染器（开发画布与运维弹框共用）。
 *
 * 从 workflow-canvas-view 抽出 ReactFlow 核心渲染逻辑，保证两处展示 100% 一致。
 * 只读模式下关闭所有编辑交互（拖拽/连线/删除/右键菜单）。
 */
import type { ReactNode } from "react"
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
  onEdgeContextMenu?: (e: React.MouseEvent, edge: Edge) => void
  onPaneClick?: () => void
  onNodeClick?: () => void
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
      onPaneClick={readOnly ? undefined : onPaneClick}
      onNodeClick={readOnly ? undefined : onNodeClick}
      onMoveStart={readOnly ? undefined : onMoveStart}
    >
      <Background />
      <Controls showInteractive={!readOnly} />
      {showMiniMap && <MiniMap pannable zoomable />}
      {children}
    </ReactFlow>
  )
}
