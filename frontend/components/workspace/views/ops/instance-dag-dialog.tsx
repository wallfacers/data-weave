"use client"

/**
 * 实例 DAG 弹窗 —— 展示任务流实例运行时 DAG，节点叠加实例状态。
 *
 * 从 useInstanceDag hook 获取数据，委托 {@link DagDialog} 渲染。
 * 与 DagViewerDialog 共享同一弹窗外壳，差异仅在于数据源和侧面板内容。
 *
 * 右键节点弹出上下文菜单，支持「查看详情」和「查看日志」。
 */

import { useCallback, useMemo, useState } from "react"
import { createPortal } from "react-dom"
import { useTranslations } from "next-intl"
import { type Node, type Edge } from "@xyflow/react"

import { DagDialog } from "@/components/workspace/dag-dialog"
import { InstanceDetailSidePanel } from "./instance-detail-side-panel"
import { instanceDagViewToFlow } from "@/lib/workspace/dag-helpers"
import { useInstanceDag } from "@/lib/hooks/use-instance-dag"

export interface InstanceDagDialogProps {
  workflowInstanceId: string | null
  /** 从任务实例列表进入时高亮的目标节点 taskInstanceId */
  highlightTaskInstanceId?: string | null
  open: boolean
  onOpenChange: (open: boolean) => void
}

export function InstanceDagDialog({
  workflowInstanceId,
  highlightTaskInstanceId,
  open,
  onOpenChange,
}: InstanceDagDialogProps) {
  const t = useTranslations("ops")
  const stateLabel = (s: string) => t(`state${s}` as any) || s
  const triggerLabel = (tr: string) => {
    switch (tr) {
      case "CRON": return t("triggerTypeCron")
      case "MANUAL": return t("triggerTypeManual")
      case "BACKFILL": return t("triggerTypeBackfill")
      default: return tr
    }
  }
  const { dag, loading, error, reload } = useInstanceDag(open ? workflowInstanceId : null)
  const [selectedTaskInstanceId, setSelectedTaskInstanceId] = useState<string | null>(null)
  const [selectedNodeName, setSelectedNodeName] = useState<string | undefined>(undefined)
  const [selectedTaskState, setSelectedTaskState] = useState<string | undefined>(undefined)

  // ── 右键上下文菜单 ──────────────────────────────
  const [contextMenu, setContextMenu] = useState<{ x: number; y: number; node: Node } | null>(null)

  // ── 侧面板内 Tab 切换（详情 / 日志）─────────────
  const [activeTab, setActiveTab] = useState<"detail" | "log">("detail")

  // ── 计算高亮 nodeKey ─────────────────────────────────
  const highlightNodeKey = useMemo(() => {
    if (!dag || !highlightTaskInstanceId) return null
    const node = dag.nodes.find((n) => n.taskInstanceId === highlightTaskInstanceId)
    return node?.nodeKey ?? null
  }, [dag, highlightTaskInstanceId])

  // ── 转换为 ReactFlow 格式 ────────────────────────────
  const flow = useMemo(() => {
    if (!dag) return { nodes: [] as Node[], edges: [] as Edge[] }
    return instanceDagViewToFlow(dag, highlightNodeKey)
  }, [dag, highlightNodeKey])

  // ── 交互回调 ────────────────────────────────────────

  const handleNodeClick = useCallback(
    (_event: React.MouseEvent, node: Node) => {
      const data = node.data as Record<string, unknown> | undefined
      const tiId = (data?.taskInstanceId as string) ?? null
      setSelectedTaskInstanceId(tiId)
      setSelectedNodeName((data?.label as string) ?? undefined)
      setSelectedTaskState((data?.state as string) ?? undefined)
      setActiveTab("detail")
      setContextMenu(null)
    },
    [],
  )

  const handlePaneClick = useCallback(() => {
    setSelectedTaskInstanceId(null)
    setSelectedNodeName(undefined)
    setContextMenu(null)
  }, [])

  const handleNodeContextMenu = useCallback(
    (event: React.MouseEvent, node: Node) => {
      event.preventDefault()
      const data = node.data as Record<string, unknown> | undefined
      if (data?.taskInstanceId) {
        setContextMenu({ x: event.clientX, y: event.clientY, node })
      }
    },
    [],
  )

  const handleContextMenuViewDetail = useCallback(() => {
    const node = contextMenu?.node
    if (node) {
      const data = node.data as Record<string, unknown> | undefined
      setSelectedTaskInstanceId((data?.taskInstanceId as string) ?? null)
      setSelectedNodeName((data?.label as string) ?? undefined)
      setSelectedTaskState((data?.state as string) ?? undefined)
      setActiveTab("detail")
    }
    setContextMenu(null)
  }, [contextMenu])

  const handleContextMenuViewLog = useCallback(() => {
    const node = contextMenu?.node
    if (node) {
      const data = node.data as Record<string, unknown> | undefined
      setSelectedTaskInstanceId((data?.taskInstanceId as string) ?? null)
      setSelectedNodeName((data?.label as string) ?? undefined)
      setSelectedTaskState((data?.state as string) ?? undefined)
      setActiveTab("log")
    }
    setContextMenu(null)
  }, [contextMenu])

  const handleOpenChange = useCallback(
    (next: boolean) => {
      if (!next) {
        setSelectedTaskInstanceId(null)
        setSelectedNodeName(undefined)
        setContextMenu(null)
        setActiveTab("detail")
      }
      onOpenChange(next)
    },
    [onOpenChange],
  )

  // ── DagDialog props ─────────────────────────────────

  const panelOpen = selectedTaskInstanceId !== null

  const title = dag ? `${dag.workflowName} — ${dag.bizDate}` : t("instanceDagTitle")
  const envLabel = dag?.env ? ` · ${dag.env}` : ""
  const subtitle = dag ? `v${dag.workflowVersionNo} · ${triggerLabel(dag.triggerType)} · ${stateLabel(dag.state)}${envLabel}` : undefined
  const footerInfo = dag
    ? `v${dag.workflowVersionNo} · ${triggerLabel(dag.triggerType)} · ${dag.bizDate}${envLabel}`
    : " "

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
          <button
            className="flex w-full cursor-default select-none items-center gap-2 rounded-md px-2 py-1.5 text-sm outline-none hover:bg-accent hover:text-accent-foreground"
            onClick={handleContextMenuViewDetail}
          >
            {t("nodeDetail.viewTaskDetail")}
          </button>
          <button
            className="flex w-full cursor-default select-none items-center gap-2 rounded-md px-2 py-1.5 text-sm outline-none hover:bg-accent hover:text-accent-foreground"
            onClick={handleContextMenuViewLog}
          >
            {t("nodeDetail.viewLog")}
          </button>
        </div>
      </>,
      document.body,
    )
  }, [contextMenu, handleContextMenuViewDetail, handleContextMenuViewLog, t])

  return (
    <DagDialog
      open={open}
      onOpenChange={handleOpenChange}
      loading={loading}
      error={error}
      onRetry={reload}
      hasData={dag !== null}
      flowNodes={flow.nodes}
      flowEdges={flow.edges}
      title={title}
      subtitle={subtitle}
      footerInfo={footerInfo}
      panelOpen={panelOpen}
      renderSidePanel={() => (
        <InstanceDetailSidePanel
          taskInstanceId={selectedTaskInstanceId}
          nodeName={selectedNodeName}
          taskState={selectedTaskState}
          env={dag?.env}
          activeTab={activeTab}
          onTabChange={setActiveTab}
          onClose={handlePaneClick}
        />
      )}
      panelStorageKey="dw.instanceDag.panelWidth"
      rfId={`instance-dag-${workflowInstanceId}`}
      onNodeClick={handleNodeClick}
      onNodeContextMenu={handleNodeContextMenu}
      onPaneClick={handlePaneClick}
      renderContextMenu={renderContextMenu}
      hasActivePanel={panelOpen}
    />
  )
}
