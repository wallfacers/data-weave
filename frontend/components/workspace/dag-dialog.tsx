"use client"

/**
 * 公共 DAG 弹窗组件 —— 周期任务流 DAG 和任务流实例 DAG 的统一外壳。
 *
 * 封装 Dialog 布局、面板拖拽、DAG 渲染、loading/error/empty 状态展示。
 * 两个场景通过 props 注入不同的数据源、侧面板内容、标题信息。
 */
import { useCallback, useLayoutEffect, useMemo, useRef, useState, type ReactNode, type MouseEvent as ReactMouseEvent } from "react"
import { useTranslations } from "next-intl"
import { motion, useMotionValue, useTransform } from "motion/react"
import { ReactFlowProvider, type Node, type Edge, type NodeTypes } from "@xyflow/react"
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
import { DagRenderer } from "@/components/workspace/dag-renderer"
import { TaskNode } from "@/components/workspace/nodes/task-node"
import { VirtualNode } from "@/components/workspace/nodes/virtual-node"

// ─── Helpers ─────────────────────────────────────────

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

const PANEL_MIN_WIDTH = 280
const PANEL_MAX_FRACTION = 1 / 3

// ─── Props ───────────────────────────────────────────

export interface DagDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void

  // ── 数据状态 ──
  loading: boolean
  error: string | null
  onRetry: () => void
  /** DAG 数据是否就绪（false 且非 loading/error → 空状态） */
  hasData: boolean
  flowNodes: Node[]
  flowEdges: Edge[]

  // ── 内容 ──
  title: ReactNode
  subtitle?: ReactNode
  footerInfo: ReactNode

  // ── 面板 ──
  panelOpen: boolean
  renderSidePanel: () => ReactNode
  panelStorageKey: string

  // ── DAG 交互 ──
  rfId: string
  onNodeClick?: (event: ReactMouseEvent, node: Node) => void
  onNodeContextMenu?: (event: ReactMouseEvent, node: Node) => void
  onPaneClick?: () => void

  // ── 额外功能 ──
  /** 右键上下文菜单渲染（仅设计态 DAG 需要） */
  renderContextMenu?: () => ReactNode

  /** 是否有可用面板（用于 Escape 键行为：先关面板再关弹窗） */
  hasActivePanel?: boolean
}

// ─── Inner component (needs ReactFlowProvider) ───────

function DagDialogInner({
  open,
  onOpenChange,
  loading,
  error,
  onRetry,
  hasData,
  flowNodes,
  flowEdges,
  title,
  subtitle,
  footerInfo,
  panelOpen,
  renderSidePanel,
  panelStorageKey,
  rfId,
  onNodeClick,
  onNodeContextMenu,
  onPaneClick,
  renderContextMenu,
  hasActivePanel,
}: DagDialogProps) {
  const t = useTranslations("ops")
  const tc = useTranslations("common")

  const nodeTypes: NodeTypes = useMemo(() => ({ task: TaskNode, virtual: VirtualNode }), [])

  // ── 面板可拖拽宽度 ─────────────────────────────────
  const dialogRef = useRef<HTMLDivElement>(null)
  const [, setPanelWidth] = useState(0)
  const panelWidthMotion = useMotionValue(
    typeof window !== "undefined"
      ? calcPanelWidth(window.innerWidth * 0.9, PANEL_MIN_WIDTH, PANEL_MAX_FRACTION, panelStorageKey)
      : PANEL_MIN_WIDTH,
  )
  const panelWidthStyle = useTransform(panelWidthMotion, (v) => `${Math.round(v)}px`)

  // 弹窗打开后校准宽度
  useLayoutEffect(() => {
    const dialog = dialogRef.current
    if (!dialog) return
    const dialogW = dialog.offsetWidth
    const w = calcPanelWidth(dialogW, PANEL_MIN_WIDTH, PANEL_MAX_FRACTION, panelStorageKey)
    panelWidthMotion.set(w)
    setPanelWidth(w)
  }, [panelWidthMotion, open, hasData])

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
        current = Math.min(maxW, Math.max(PANEL_MIN_WIDTH, startW - (ev.clientX - startX)))
        panelWidthMotion.set(current)
      }
      const onUp = () => {
        window.removeEventListener("pointermove", onMove)
        window.removeEventListener("pointerup", onUp)
        document.body.style.cursor = ""
        document.body.style.userSelect = ""
        setPanelWidth(current)
        localStorage.setItem(panelStorageKey, String(current))
      }
      document.body.style.cursor = "col-resize"
      document.body.style.userSelect = "none"
      window.addEventListener("pointermove", onMove)
      window.addEventListener("pointerup", onUp)
    },
    [panelWidthMotion, panelStorageKey],
  )

  // Escape 键：有活跃面板时先关面板，否则由 Dialog 原生处理关闭
  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === "Escape" && hasActivePanel) {
        e.stopPropagation()
        onPaneClick?.()
      }
    },
    [hasActivePanel, onPaneClick],
  )

  // 关闭弹窗时重置面板
  const handleOpenChange = useCallback(
    (next: boolean) => {
      if (!next) onPaneClick?.() // 重置面板状态
      onOpenChange(next)
    },
    [onOpenChange, onPaneClick],
  )

  const showEmpty = !loading && !error && !hasData

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent
        className="max-w-[90vw] max-h-[90vh] w-[90vw] h-[90vh] flex flex-col p-0"
        showCloseButton={false}
        onKeyDown={hasActivePanel ? handleKeyDown : undefined}
      >
        {/* Header */}
        <DialogHeader className="shrink-0 px-6 pt-5 pb-0">
          <DialogTitle className="text-base">{title}</DialogTitle>
          {subtitle && (
            <p className="text-xs text-muted-foreground mt-1">{subtitle}</p>
          )}
        </DialogHeader>

        {/* Body */}
        <div className="flex-1 min-h-0 flex flex-row relative" ref={dialogRef}>
          {/* 左侧 DAG 区域 */}
          <div className="flex-1 min-w-0 relative">
            {loading && (
              <div className="absolute inset-0 flex items-center justify-center gap-2 text-muted-foreground">
                <HugeiconsIcon icon={RefreshIcon} className="size-5 animate-spin" />
                <span className="text-sm">{tc("loading")}</span>
              </div>
            )}

            {error && (
              <div className="absolute inset-0 flex flex-col items-center justify-center gap-3 text-muted-foreground">
                <span className="text-sm text-destructive">{error}</span>
                <Button variant="outline" size="sm" onClick={onRetry}>
                  {t("dagRetry")}
                </Button>
              </div>
            )}

            {showEmpty && (
              <div className="absolute inset-0 flex flex-col items-center justify-center gap-2 text-muted-foreground">
                <span className="text-sm">{t("dagEmpty")}</span>
              </div>
            )}

            {!loading && !error && hasData && (
              <DagRenderer
                rfId={rfId}
                nodes={flowNodes}
                edges={flowEdges}
                nodeTypes={nodeTypes}
                readOnly
                onNodeClick={onNodeClick}
                onNodeContextMenu={onNodeContextMenu}
                onPaneClick={onPaneClick}
              />
            )}
          </div>

          {/* 右侧详情面板 */}
          {hasData && panelOpen && (
            <motion.div
              className="shrink-0 h-full mr-3 relative"
              style={{ width: panelWidthStyle }}
            >
              {/* 分割线 */}
              <div
                onPointerDown={onPanelResizeDown}
                role="separator"
                aria-orientation="vertical"
                aria-label="Resize panel"
                className="group/resize absolute inset-y-3 -left-1 z-20 flex w-2 cursor-col-resize touch-none items-center justify-center"
              >
                <div className="h-12 w-0.5 rounded-full bg-border/0 transition-colors group-hover/resize:bg-border" />
              </div>
              {renderSidePanel()}
            </motion.div>
          )}

          {/* 右键上下文菜单（portal 由外部通过 renderContextMenu 处理） */}
          {renderContextMenu?.()}
        </div>

        {/* Footer */}
        <DialogFooter className="shrink-0 flex items-center justify-between px-6 pb-5 pt-0">
          <span className="text-xs text-muted-foreground">{footerInfo}</span>
          <DialogClose render={<Button variant="ghost" size="sm" />}>
            {t("dagViewer.close")}
          </DialogClose>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

// ─── Wrapped in ReactFlowProvider ────────────────────

export function DagDialog(props: DagDialogProps) {
  return (
    <ReactFlowProvider>
      <DagDialogInner {...props} />
    </ReactFlowProvider>
  )
}
