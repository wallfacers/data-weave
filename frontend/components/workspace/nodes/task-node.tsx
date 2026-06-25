"use client"

import { useContext } from "react"
import { useTranslations } from "next-intl"
import { Handle, Position, type NodeProps } from "@xyflow/react"
import { HugeiconsIcon } from "@hugeicons/react"
import { DatabaseIcon } from "@hugeicons/core-free-icons"

import {
  ContextMenu,
  ContextMenuTrigger,
  ContextMenuContent,
  ContextMenuItem,
  ContextMenuSeparator,
} from "@/components/ui/context-menu"
import { type CanvasNode, runStateDotClass } from "./canvas-node-types"
import { NodeActionsContext } from "./node-actions-context"

function RunStateDot({ state }: { state?: string }) {
  if (!state) return null
  return (
    <span
      title={state}
      className={`absolute -right-1 -top-1 size-2 rounded-full ring-2 ring-card ${runStateDotClass(state)}`}
    />
  )
}

export function TaskNode({ id, data, selected }: NodeProps<CanvasNode>) {
  const t = useTranslations("workflowCanvas")
  const actions = useContext(NodeActionsContext)
  const label = data.label || t("nodeTaskFallback")
  const taskId = data.taskId
  const canLog = taskId != null && (actions?.canViewLog(taskId) ?? false)
  const unpublished = taskId != null && data.taskStatus != null && data.taskStatus !== "ONLINE"

  const content = (
    <div
      className={`relative flex items-center gap-2 rounded-md border bg-card px-3 py-2 text-xs shadow-sm ${
        selected ? "border-primary ring-1 ring-primary" : unpublished ? "border-warning border-dashed" : "border-border"
      }`}
    >
      <Handle type="target" position={Position.Left} />
      <HugeiconsIcon icon={DatabaseIcon} className="size-4 text-primary" />
      <span className="max-w-40 truncate font-medium">{label}</span>
      {unpublished && (
        <span className="rounded bg-warning/15 px-1 py-0.5 text-[10px] font-medium leading-none text-warning">
          {t("nodeUnpublished")}
        </span>
      )}
      <Handle type="source" position={Position.Right} />
      <RunStateDot state={data.runState} />
    </div>
  )

  // Read-only mode: no context menu, no actions
  if (!actions) return content

  return (
    <ContextMenu>
      <ContextMenuTrigger>{content}</ContextMenuTrigger>
      <ContextMenuContent>
        <ContextMenuItem
          disabled={!canLog}
          onClick={() => taskId != null && actions?.onViewLog(taskId, label)}
        >
          {t("nodeMenuViewLog")}
        </ContextMenuItem>
        <ContextMenuItem
          disabled={taskId == null}
          onClick={() => taskId != null && actions?.onRunNode(taskId, label)}
        >
          {t("nodeMenuRunNode")}
        </ContextMenuItem>
        <ContextMenuItem disabled={!actions?.online} onClick={() => actions?.onRunToNode(id)}>
          {t("nodeMenuRunToNode")}
        </ContextMenuItem>
        <ContextMenuItem disabled={!actions?.online} onClick={() => actions?.onRunDownstream(id)}>
          {t("nodeMenuRunDownstream")}
        </ContextMenuItem>
        <ContextMenuSeparator />
        <ContextMenuItem variant="destructive" onClick={() => actions?.onDeleteNode(id)}>
          {t("nodeMenuDelete")}
        </ContextMenuItem>
      </ContextMenuContent>
    </ContextMenu>
  )
}
