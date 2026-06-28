"use client"

/**
 * 运维 DAG 只读查看弹框（ops-center-dag-viewer）。
 *
 * 从 GET /api/workflows/{id}/published-dag 拉取已发布版本的 DAG 快照，
 * 委托 {@link DagDialog} 渲染（与实例 DAG 弹窗共享同一外壳）。
 */
import { useCallback, useEffect, useMemo, useState } from "react"
import { createPortal } from "react-dom"
import { useTranslations } from "next-intl"
import { type Node, type Edge } from "@xyflow/react"

import { authFetch, API_BASE, type DagView } from "@/lib/types"
import { dagViewToFlow } from "@/lib/workspace/dag-helpers"
import { CanvasNodeData } from "@/components/workspace/nodes/canvas-node-types"
import { DagDialog } from "@/components/workspace/dag-dialog"
import { NodeDetailPanel } from "./node-detail-panel"
import { useNodeDetailStore } from "@/lib/workspace/node-detail-store"

export interface DagViewerDialogProps {
  workflowId: number
  workflowName: string
  open: boolean
  onOpenChange: (open: boolean) => void
}

type LoadState =
  | { kind: "loading" }
  | { kind: "loaded"; nodes: Node[]; edges: Edge[]; versionNo: number }
  | { kind: "empty"; versionNo: number }
  | { kind: "error"; message: string }

export function DagViewerDialog({
  workflowId,
  workflowName,
  open,
  onOpenChange,
}: DagViewerDialogProps) {
  const t = useTranslations("ops")
  const [state, setState] = useState<LoadState>({ kind: "loading" })

  const selectedNode = useNodeDetailStore((s) => s.selectedNode)
  const selectNode = useNodeDetailStore((s) => s.selectNode)
  const deselectNode = useNodeDetailStore((s) => s.deselectNode)
  const panelOpen = selectedNode !== null

  // 右键上下文菜单状态
  const [contextMenu, setContextMenu] = useState<{ x: number; y: number; node: Node } | null>(null)

  // ── 数据加载 ────────────────────────────────────────

  const load = useCallback(() => {
    setState({ kind: "loading" })
    authFetch(`${API_BASE}/api/workflows/${workflowId}/published-dag`, { cache: "no-store" })
      .then((r) => r.json())
      .then((j) => {
        if (j.code === 0 && j.data) {
          const dag: DagView = j.data
          const { nodes, edges } = dagViewToFlow(dag)
          if (nodes.length === 0) {
            setState({ kind: "empty", versionNo: dag.version })
          } else {
            setState({ kind: "loaded", nodes, edges, versionNo: dag.version })
          }
        } else {
          setState({ kind: "error", message: j.message || t("dagViewer.error") })
        }
      })
      .catch(() => setState({ kind: "error", message: t("dagViewer.error") }))
  }, [workflowId, t])

  useEffect(() => {
    if (open) load()
  }, [open, load])

  // ── 交互回调 ────────────────────────────────────────

  const handleOpenChange = useCallback(
    (v: boolean) => {
      if (!v) {
        setState({ kind: "loading" })
        deselectNode()
        setContextMenu(null)
      }
      onOpenChange(v)
    },
    [onOpenChange, deselectNode],
  )

  const handleNodeClick = useCallback(
    (_event: React.MouseEvent, node: Node) => {
      const d = node.data as CanvasNodeData
      if (d.nodeType === "TASK" && d.taskId) {
        selectNode(workflowId, node.id, d.taskId, d.taskVersionNo)
      }
    },
    [workflowId, selectNode],
  )

  const handlePaneClick = useCallback(() => {
    deselectNode()
    setContextMenu(null)
  }, [deselectNode])

  const handleNodeContextMenu = useCallback(
    (event: React.MouseEvent, node: Node) => {
      event.preventDefault()
      const d = node.data as CanvasNodeData
      if (d.nodeType === "TASK" && d.taskId) {
        setContextMenu({ x: event.clientX, y: event.clientY, node })
      }
    },
    [],
  )

  const handleContextMenuView = useCallback(() => {
    const node = contextMenu?.node
    if (node) {
      const d = node.data as CanvasNodeData
      if (d.taskId) {
        selectNode(workflowId, node.id, d.taskId, d.taskVersionNo)
      }
    }
    setContextMenu(null)
  }, [contextMenu, workflowId, selectNode])

  // ── DagDialog props ─────────────────────────────────

  const hasData = state.kind === "loaded" || state.kind === "empty"

  const footerInfo = useMemo(() => {
    if (state.kind === "loaded" || state.kind === "empty") {
      return t("dagViewer.versionInfo", { versionNo: state.versionNo, publishedAt: "" })
    }
    return " "
  }, [state, t])

  const renderContextMenu = useCallback(() => {
    if (!contextMenu) return null
    return createPortal(
      <>
        <div
          className="fixed inset-0 z-40"
          onClick={() => setContextMenu(null)}
          onContextMenu={(e) => { e.preventDefault(); setContextMenu(null) }}
        />
        <div
          className="fixed z-50 min-w-44 rounded-lg border bg-popover bg-clip-padding p-1 text-popover-foreground shadow-md"
          style={{ left: contextMenu.x, top: contextMenu.y }}
          onClick={() => setContextMenu(null)}
        >
          <div
            className="flex cursor-default select-none items-center gap-2 rounded-md px-2 py-1.5 text-sm outline-none hover:bg-accent hover:text-accent-foreground"
            onClick={handleContextMenuView}
          >
            {t("nodeDetail.viewTaskDetail")}
          </div>
        </div>
      </>,
      document.body,
    )
  }, [contextMenu, handleContextMenuView, t])

  return (
    <DagDialog
      open={open}
      onOpenChange={handleOpenChange}
      loading={state.kind === "loading"}
      error={state.kind === "error" ? state.message : null}
      onRetry={load}
      hasData={hasData}
      flowNodes={state.kind === "loaded" ? state.nodes : []}
      flowEdges={state.kind === "loaded" ? state.edges : []}
      title={workflowName}
      footerInfo={footerInfo}
      panelOpen={panelOpen}
      renderSidePanel={() => <NodeDetailPanel />}
      panelStorageKey="dw.dagViewer.panelWidth"
      rfId={`dagviewer-${workflowId}`}
      onNodeClick={handleNodeClick}
      onNodeContextMenu={handleNodeContextMenu}
      onPaneClick={handlePaneClick}
      renderContextMenu={renderContextMenu}
      hasActivePanel={panelOpen}
    />
  )
}
