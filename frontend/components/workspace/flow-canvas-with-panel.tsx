"use client"

/**
 * 通用「画布 + 可拖拽宽度嵌入面板」壳（与 Dialog 无关）。
 *
 * 从 DagDialogInner（dag-dialog.tsx）抽出「画布 + 可调宽嵌入面板 + loading/error/empty」
 * 那段 body，剥离 Dialog 依赖，供工作流 DAG 弹窗与血缘探索器三栏视图共用（reuse-first）。
 *
 * 内置 ReactFlowProvider（DagRenderer 不自带，非 Dialog 使用方需自包一层）。
 * 面板宽度按容器 1/4、夹在 [PANEL_MIN_WIDTH, 容器×1/3]，拖拽记忆到 localStorage。
 *
 * 设计约束（DESIGN.md）：语义 token、无分割线、不手写 dark:、加载不用省略号。
 */
import {
  useCallback,
  useLayoutEffect,
  useRef,
  useState,
  type ReactNode,
  type MouseEvent as ReactMouseEvent,
} from "react"
import { useTranslations } from "next-intl"
import { motion, useMotionValue, useTransform } from "motion/react"
import { ReactFlowProvider, type Node, type Edge, type NodeTypes } from "@xyflow/react"
import "@xyflow/react/dist/style.css"

import { Button } from "@/components/ui/button"
import { LoadingState } from "@/components/workspace/shared/loading-state"
import { DagRenderer } from "@/components/workspace/dag-renderer"

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

export const FLOW_PANEL_MIN_WIDTH = 280
export const FLOW_PANEL_MAX_FRACTION = 1 / 3

// ─── Props ───────────────────────────────────────────

export interface FlowCanvasWithPanelProps {
  /** ReactFlow instance id（同一页面多个实例时需唯一）。 */
  rfId: string
  nodes: Node[]
  edges: Edge[]
  nodeTypes: NodeTypes

  // ── 数据状态 ──
  loading: boolean
  error: string | null
  onRetry: () => void
  /** DAG 数据是否就绪（false 且非 loading/error → 空状态） */
  hasData: boolean

  // ── 画布交互 ──
  onNodeClick?: (event: ReactMouseEvent, node: Node) => void
  onNodeContextMenu?: (event: ReactMouseEvent, node: Node) => void
  /** 点画布空白 / Escape 关面板均经此回调（由调用方决定语义）。 */
  onPaneClick?: () => void
  showMiniMap?: boolean

  // ── 嵌入面板 ──
  panelOpen: boolean
  renderPanel: () => ReactNode
  panelStorageKey: string

  // ── 文案覆盖（默认取 ops / common 命名空间）──
  loadingText?: string
  emptyText?: string
  retryText?: string

  /** 画布层级额外浮层（如右键菜单、图例），渲染在画布与面板之上。 */
  children?: ReactNode
}

// ─── Component ───────────────────────────────────────

export function FlowCanvasWithPanel({
  rfId,
  nodes,
  edges,
  nodeTypes,
  loading,
  error,
  onRetry,
  hasData,
  onNodeClick,
  onNodeContextMenu,
  onPaneClick,
  showMiniMap = false,
  panelOpen,
  renderPanel,
  panelStorageKey,
  loadingText,
  emptyText,
  retryText,
  children,
}: FlowCanvasWithPanelProps) {
  const t = useTranslations("ops")
  const tc = useTranslations("common")

  const containerRef = useRef<HTMLDivElement>(null)
  const [, setPanelWidth] = useState(0)
  const panelWidthMotion = useMotionValue(
    typeof window !== "undefined"
      ? calcPanelWidth(window.innerWidth, FLOW_PANEL_MIN_WIDTH, FLOW_PANEL_MAX_FRACTION, panelStorageKey)
      : FLOW_PANEL_MIN_WIDTH,
  )
  const panelWidthStyle = useTransform(panelWidthMotion, (v) => `${Math.round(v)}px`)

  // 容器挂载/数据就绪后校准宽度
  useLayoutEffect(() => {
    const el = containerRef.current
    if (!el) return
    const w = calcPanelWidth(el.offsetWidth, FLOW_PANEL_MIN_WIDTH, FLOW_PANEL_MAX_FRACTION, panelStorageKey)
    panelWidthMotion.set(w)
    setPanelWidth(w)
  }, [panelWidthMotion, panelStorageKey, hasData])

  // 分割线拖拽
  const onPanelResizeDown = useCallback(
    (e: React.PointerEvent) => {
      e.preventDefault()
      const startX = e.clientX
      const el = containerRef.current
      if (!el) return
      const containerW = el.offsetWidth
      const maxW = Math.floor(containerW * FLOW_PANEL_MAX_FRACTION)
      const startW = panelWidthMotion.get()
      let current = startW
      const onMove = (ev: PointerEvent) => {
        current = Math.min(maxW, Math.max(FLOW_PANEL_MIN_WIDTH, startW - (ev.clientX - startX)))
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

  const showEmpty = !loading && !error && !hasData

  return (
    <ReactFlowProvider>
      <div className="flex-1 min-h-0 flex flex-row relative" ref={containerRef}>
        {/* 画布区域 */}
        <div className="flex-1 min-w-0 relative">
          {loading && <LoadingState variant="overlay" active={loading} text={loadingText ?? tc("loading")} />}

          {error && (
            <div className="absolute inset-0 z-10 flex flex-col items-center justify-center gap-3 text-muted-foreground">
              <span className="text-sm text-destructive">{error}</span>
              <Button variant="outline" size="sm" onClick={onRetry}>
                {retryText ?? t("dagRetry")}
              </Button>
            </div>
          )}

          {showEmpty && (
            <div className="absolute inset-0 flex flex-col items-center justify-center gap-2 text-muted-foreground">
              <span className="text-sm">{emptyText ?? t("dagEmpty")}</span>
            </div>
          )}

          {!loading && !error && hasData && (
            <DagRenderer
              rfId={rfId}
              nodes={nodes}
              edges={edges}
              nodeTypes={nodeTypes}
              readOnly
              showMiniMap={showMiniMap}
              onNodeClick={onNodeClick}
              onNodeContextMenu={onNodeContextMenu}
              onPaneClick={onPaneClick}
            />
          )}

          {/* 画布层级浮层（图例等），不阻塞画布交互（pointer-events-none 由调用方控制） */}
          {children}
        </div>

        {/* 右侧嵌入面板（可拖拽宽度） */}
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
            {renderPanel()}
          </motion.div>
        )}
      </div>
    </ReactFlowProvider>
  )
}
