"use client"

import { useContext } from "react"
import { useTranslations } from "next-intl"
import { Handle, Position, type NodeProps } from "@xyflow/react"
import { HugeiconsIcon } from "@hugeicons/react"
import { CircleIcon } from "@hugeicons/core-free-icons"

import {
  ContextMenu,
  ContextMenuTrigger,
  ContextMenuContent,
  ContextMenuItem,
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

export function VirtualNode({ id, data, selected }: NodeProps<CanvasNode>) {
  const t = useTranslations("workflowCanvas")
  const actions = useContext(NodeActionsContext)

  const content = (
    <div
      className={`relative flex items-center gap-2 rounded-full border border-dashed bg-muted px-3 py-2 text-xs ${
        selected ? "border-primary ring-1 ring-primary" : "border-muted-foreground/40"
      }`}
    >
      <Handle type="target" position={Position.Left} />
      <HugeiconsIcon icon={CircleIcon} className="size-4 text-muted-foreground" />
      <span className="max-w-40 truncate text-muted-foreground">{data.label || t("nodeVirtualFallback")}</span>
      <Handle type="source" position={Position.Right} />
      <RunStateDot state={data.runState} />
    </div>
  )

  // Read-only mode: no context menu
  if (!actions) return content

  return (
    <ContextMenu>
      <ContextMenuTrigger>{content}</ContextMenuTrigger>
      <ContextMenuContent>
        <ContextMenuItem variant="destructive" onClick={() => actions?.onDeleteNode(id)}>
          {t("nodeMenuDelete")}
        </ContextMenuItem>
      </ContextMenuContent>
    </ContextMenu>
  )
}
