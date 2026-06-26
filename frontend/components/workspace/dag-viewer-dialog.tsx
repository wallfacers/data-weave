"use client"

/**
 * 运维 DAG 只读查看弹框（ops-center-dag-viewer）。
 *
 * 从 GET /api/workflows/{id}/published-dag 拉取已发布版本的 DAG 快照，
 * 复用 {@link DagRenderer}（与开发画布完全相同的渲染逻辑）以只读模式展示线上拓扑。
 */
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react"
import { createPortal } from "react-dom"
import { useTranslations } from "next-intl"
import { motion, useMotionValue, useTransform } from "motion/react"
import { ReactFlowProvider, type Node, type Edge } from "@xyflow/react"
import "@xyflow/react/dist/style.css"

import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Button } from "@/components/ui/button"
import { HugeiconsIcon } from "@hugeicons/react"
import { RefreshIcon } from "@hugeicons/core-free-icons"
import { authFetch, API_BASE, type DagView } from "@/lib/types"
import { dagViewToFlow } from "@/lib/workspace/dag-helpers"
import { TaskNode } from "@/components/workspace/nodes/task-node"
import { VirtualNode } from "@/components/workspace/nodes/virtual-node"
import { CanvasNodeData } from "@/components/workspace/nodes/canvas-node-types"
import { DagRenderer } from "./dag-renderer"
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

/** 计算面板宽度：优先 localStorage，否则按容器宽度的 1/4，夹在 [minW, maxFraction] 之间 */
function calcPanelWidth(
  containerW: number,
  minW: number,
  maxFraction: number,
  storageKey: string,
): number {
  const maxW = Math.floor(containerW * maxFraction)
  const saved = Number(localStorage.getItem(storageKey))
  if (saved >= minW && saved <= maxW) return saved
  return Math.max(minW, Math.min(Math.floor(containerW / 4), maxW))
}

function DagViewerDialogInner({
  workflowId,
  workflowName,
  open,
  onOpenChange,
}: DagViewerDialogProps) {
  const t = useTranslations("ops")
  const tc = useTranslations("common")
  const [state, setState] = useState<LoadState>({ kind: "loading" })

  const selectedNode = useNodeDetailStore((s) => s.selectedNode)
  const selectNode = useNodeDetailStore((s) => s.selectNode)
  const deselectNode = useNodeDetailStore((s) => s.deselectNode)
  const panelOpen = selectedNode !== null

  // 右键上下文菜单状态
  const [contextMenu, setContextMenu] = useState<{ x: number; y: number; node: Node } | null>(null)

  // ── 面板宽度（可拖拽分割线调整，localStorage 持久化）────────────
  const PANEL_MIN_WIDTH = 280
  const PANEL_MAX_FRACTION = 1 / 3
  const PANEL_KEY = "dw.dagViewer.panelWidth"
  const dialogRef = useRef<HTMLDivElement>(null)
  const [, setPanelWidth] = useState(0) // 基准值，拖拽松手时同步

  // 用 viewport 估算默认宽度，防止 Dialog 未挂载时 dialogRef.current=null 导致宽度=0
  const panelWidthMotion = useMotionValue(
    typeof window !== "undefined"
      ? calcPanelWidth(window.innerWidth * 0.9, PANEL_MIN_WIDTH, PANEL_MAX_FRACTION, PANEL_KEY)
      : PANEL_MIN_WIDTH,
  )
  const panelWidthStyle = useTransform(panelWidthMotion, (v) => `${Math.round(v)}px`)

  // 对话框打开后从真实 dialog 宽度校正；同时加载 localStorage 偏好
  useLayoutEffect(() => {
    const dialog = dialogRef.current
    if (!dialog) return
    const dialogW = dialog.offsetWidth
    const w = calcPanelWidth(dialogW, PANEL_MIN_WIDTH, PANEL_MAX_FRACTION, PANEL_KEY)
    panelWidthMotion.set(w)
    setPanelWidth(w)
  }, [panelWidthMotion, open, state.kind])

  // 分割线拖拽
  const onPanelResizeDown = useCallback(
    (e: React.PointerEvent) => {
      e.preventDefault()
      const startX = e.clientX
      const dialog = dialogRef.current
      if (!dialog) return
      const dialogW = dialog.offsetWidth
      const maxW = Math.floor(dialogW * PANEL_MAX_FRACTION)
      const startW = panelWidthMotion.get()
      let current = startW
      const onMove = (ev: PointerEvent) => {
        // 拖拽分割线向右 = 面板变窄（因为面板在右）
        current = Math.min(maxW, Math.max(PANEL_MIN_WIDTH, startW - (ev.clientX - startX)))
        panelWidthMotion.set(current)
      }
      const onUp = () => {
        window.removeEventListener("pointermove", onMove)
        window.removeEventListener("pointerup", onUp)
        document.body.style.cursor = ""
        document.body.style.userSelect = ""
        setPanelWidth(current)
        localStorage.setItem(PANEL_KEY, String(current))
      }
      document.body.style.cursor = "col-resize"
      document.body.style.userSelect = "none"
      window.addEventListener("pointermove", onMove)
      window.addEventListener("pointerup", onUp)
    },
    [panelWidthMotion],
  )

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

  const nodeTypes = useMemo(() => ({ task: TaskNode, virtual: VirtualNode }), [])

  const handleOpenChange = useCallback(
    (v: boolean) => {
      if (!v) {
        setState({ kind: "loading" })
        deselectNode()
      }
      onOpenChange(v)
    },
    [onOpenChange, deselectNode],
  )

  /** 节点点击 → 打开/切换详情面板（仅 TASK 节点） */
  const handleNodeClick = useCallback(
    (_event: React.MouseEvent, node: Node) => {
      const d = node.data as CanvasNodeData
      if (d.nodeType === "TASK" && d.taskId) {
        selectNode(workflowId, node.id, d.taskId, d.taskVersionNo)
      }
    },
    [workflowId, selectNode],
  )

  /** 画布空白区域点击 → 关闭面板 + 菜单 */
  const handlePaneClick = useCallback(() => {
    deselectNode()
    setContextMenu(null)
  }, [deselectNode])

  /** 右击节点 → 弹出上下文菜单（仅 TASK 节点显示"查看任务详情"） */
  const handleNodeContextMenu = useCallback(
    (event: React.MouseEvent, node: Node) => {
      event.preventDefault()
      const d = node.data as CanvasNodeData
      if (d.nodeType === "TASK" && d.taskId) {
        setContextMenu({ x: event.clientX, y: event.clientY, node })
      }
      // VIRTUAL 节点不弹菜单
    },
    [],
  )

  /** 上下文菜单项：查看任务详情 */
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

  /** Escape 键：先关面板，面板已关则关弹框 */
  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === "Escape") {
        if (panelOpen) {
          e.stopPropagation()
          deselectNode()
        }
        // 否则由 Dialog 原生处理关闭
      }
    },
    [panelOpen, deselectNode],
  )

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent
        className="max-w-[90vw] max-h-[90vh] w-[90vw] h-[90vh] flex flex-col p-0"
        showCloseButton={false}
        onKeyDown={handleKeyDown}
      >
        {/* Header — 仅任务流名称 */}
        <DialogHeader className="shrink-0 px-6 pt-5 pb-0">
          <DialogTitle className="text-base">{workflowName}</DialogTitle>
        </DialogHeader>

        {/* Body — flex-row 当面板打开时分栏 */}
        <div className="flex-1 min-h-0 flex flex-row relative" ref={dialogRef}>
          {/* 左侧 DAG 区域 */}
          <div className="flex-1 min-w-0 relative">
            {state.kind === "loading" && (
              <div className="absolute inset-0 flex items-center justify-center gap-2 text-muted-foreground">
                <HugeiconsIcon icon={RefreshIcon} className="size-5 animate-spin" />
                <span className="text-sm">{tc("loading")}</span>
              </div>
            )}

            {state.kind === "empty" && (
              <div className="absolute inset-0 flex flex-col items-center justify-center gap-2 text-muted-foreground">
                <span className="text-sm">{t("dagViewer.empty")}</span>
              </div>
            )}

            {state.kind === "error" && (
              <div className="absolute inset-0 flex flex-col items-center justify-center gap-3 text-muted-foreground">
                <span className="text-sm text-destructive">{state.message}</span>
                <Button variant="outline" size="sm" onClick={load}>
                  {t("dagViewer.retry")}
                </Button>
              </div>
            )}

            {state.kind === "loaded" && (
              <DagRenderer
                rfId={`dagviewer-${workflowId}`}
                nodes={state.nodes}
                edges={state.edges}
                nodeTypes={nodeTypes}
                readOnly
                onNodeClick={handleNodeClick}
                onNodeContextMenu={handleNodeContextMenu}
                onPaneClick={handlePaneClick}
              />
            )}
          </div>

          {/* 右侧详情面板（分割线在面板左边框上，同 agent-rail 样式） */}
          {state.kind === "loaded" && panelOpen && (
            <motion.div
              className="shrink-0 h-full mr-3 relative"
              style={{ width: panelWidthStyle }}
            >
              {/* 分割线：绝对定位在面板左边框，向左侧延伸命中区 */}
              <div
                onPointerDown={onPanelResizeDown}
                role="separator"
                aria-orientation="vertical"
                aria-label="Resize panel"
                className="group/resize absolute inset-y-3 -left-1 z-20 flex w-2 cursor-col-resize touch-none items-center justify-center"
              >
                <div className="h-12 w-0.5 rounded-full bg-border/0 transition-colors group-hover/resize:bg-border" />
              </div>
              <NodeDetailPanel />
            </motion.div>
          )}

          {/* 右键上下文菜单 + 点击关闭遮罩（portal 到 body，绕过 Dialog 的 CSS transform containing block） */}
          {contextMenu &&
            createPortal(
              <>
                {/* 全屏透明遮罩：点击任意位置关闭菜单 */}
                <div
                  className="fixed inset-0 z-40"
                  onClick={() => setContextMenu(null)}
                  onContextMenu={(e) => { e.preventDefault(); setContextMenu(null) }}
                />
                {/* 菜单本体：复用 ContextMenuContent + ContextMenuItem 同款样式 */}
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
            )}
        </div>

        {/* Footer */}
        <DialogFooter className="shrink-0 flex items-center justify-between px-6 pb-5 pt-0">
          <span className="text-xs text-muted-foreground">
            {state.kind === "loaded" || state.kind === "empty"
              ? t("dagViewer.versionInfo", {
                  versionNo: state.versionNo,
                  publishedAt: "",
                })
              : " "}
          </span>
          <DialogClose render={<Button variant="ghost" size="sm" />}>
            {t("dagViewer.close")}
          </DialogClose>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

/** Wrapped in ReactFlowProvider so nodeTypes/hooks work inside Dialog portal. */
export function DagViewerDialog(props: DagViewerDialogProps) {
  return (
    <ReactFlowProvider>
      <DagViewerDialogInner {...props} />
    </ReactFlowProvider>
  )
}
