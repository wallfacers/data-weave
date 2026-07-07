"use client"

/**
 * 公共 DAG 弹窗组件 —— 周期任务流 DAG 和任务流实例 DAG 的统一外壳。
 *
 * 封装 Dialog 布局（header/footer）+ Escape 面板优先关闭，画布与嵌入面板交给
 * 复用的 `FlowCanvasWithPanel`（与血缘探索器三栏视图共用同一壳，reuse-first）。
 * 两个场景通过 props 注入不同的数据源、侧面板内容、标题信息。
 */
import { useCallback, useMemo, type ReactNode, type MouseEvent as ReactMouseEvent } from "react"
import { useTranslations } from "next-intl"
import { type Node, type Edge, type NodeTypes } from "@xyflow/react"

import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Button } from "@/components/ui/button"
import { TaskNode } from "@/components/workspace/nodes/task-node"
import { VirtualNode } from "@/components/workspace/nodes/virtual-node"
import { FlowCanvasWithPanel } from "@/components/workspace/flow-canvas-with-panel"

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

// ─── Component ───────────────────────────────────────

export function DagDialog({
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

  const nodeTypes: NodeTypes = useMemo(() => ({ task: TaskNode, virtual: VirtualNode }), [])

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

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent
        className="max-w-[90vw] max-h-[90vh] w-[90vw] h-[90vh] flex flex-col p-0"
        onKeyDown={hasActivePanel ? handleKeyDown : undefined}
      >
        {/* Header */}
        <DialogHeader className="shrink-0 px-6 pt-5 pb-0">
          <DialogTitle className="text-base">{title}</DialogTitle>
          {subtitle && (
            <p className="text-xs text-muted-foreground mt-1">{subtitle}</p>
          )}
        </DialogHeader>

        {/* Body —— 画布 + 嵌入面板（复用 FlowCanvasWithPanel） */}
        <FlowCanvasWithPanel
          rfId={rfId}
          nodes={flowNodes}
          edges={flowEdges}
          nodeTypes={nodeTypes}
          loading={loading}
          error={error}
          onRetry={onRetry}
          hasData={hasData}
          onNodeClick={onNodeClick}
          onNodeContextMenu={onNodeContextMenu}
          onPaneClick={onPaneClick}
          panelOpen={panelOpen}
          renderPanel={renderSidePanel}
          panelStorageKey={panelStorageKey}
        >
          {/* 右键上下文菜单（portal 由外部通过 renderContextMenu 处理） */}
          {renderContextMenu?.()}
        </FlowCanvasWithPanel>

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
