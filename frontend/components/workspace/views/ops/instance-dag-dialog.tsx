"use client"

/**
 * 实例 DAG 弹窗 —— 展示任务流实例运行时 DAG，节点叠加实例状态。
 *
 * 从 useInstanceDag hook 获取数据，委托 {@link DagDialog} 渲染。
 * 与 DagViewerDialog 共享同一弹窗外壳，差异仅在于数据源和侧面板内容。
 */

import { useCallback, useMemo, useState } from "react"
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
  const { dag, loading, error, reload } = useInstanceDag(open ? workflowInstanceId : null)
  const [selectedTaskInstanceId, setSelectedTaskInstanceId] = useState<string | null>(null)
  const [selectedNodeName, setSelectedNodeName] = useState<string | undefined>(undefined)
  const [selectedTaskState, setSelectedTaskState] = useState<string | undefined>(undefined)

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
    },
    [],
  )

  const handlePaneClick = useCallback(() => {
    setSelectedTaskInstanceId(null)
    setSelectedNodeName(undefined)
  }, [])

  const handleOpenChange = useCallback(
    (next: boolean) => {
      if (!next) {
        setSelectedTaskInstanceId(null)
        setSelectedNodeName(undefined)
      }
      onOpenChange(next)
    },
    [onOpenChange],
  )

  // ── DagDialog props ─────────────────────────────────

  const panelOpen = selectedTaskInstanceId !== null

  const title = dag ? `${dag.workflowName} — ${dag.bizDate}` : t("instanceDagTitle")
  const envLabel = dag?.env ? ` · ${dag.env}` : ""
  const subtitle = dag ? `v${dag.workflowVersionNo} · ${dag.triggerType} · ${dag.state}${envLabel}` : undefined
  const footerInfo = dag
    ? `v${dag.workflowVersionNo} · ${dag.triggerType} · ${dag.bizDate}${envLabel}`
    : " "

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
          onClose={handlePaneClick}
        />
      )}
      panelStorageKey="dw.instanceDag.panelWidth"
      rfId={`instance-dag-${workflowInstanceId}`}
      onNodeClick={handleNodeClick}
      onPaneClick={handlePaneClick}
    />
  )
}
