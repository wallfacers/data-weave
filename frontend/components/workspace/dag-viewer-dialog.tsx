"use client"

/**
 * 运维 DAG 只读查看弹框（ops-center-dag-viewer）。
 *
 * 从 GET /api/workflows/{id}/published-dag 拉取已发布版本的 DAG 快照，
 * 复用 {@link DagRenderer}（与开发画布完全相同的渲染逻辑）以只读模式展示线上拓扑。
 */
import { useCallback, useEffect, useMemo, useState } from "react"
import { useTranslations } from "next-intl"
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
import { DagRenderer } from "./dag-renderer"

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

function DagViewerDialogInner({
  workflowId,
  workflowName,
  open,
  onOpenChange,
}: DagViewerDialogProps) {
  const t = useTranslations("ops")
  const tc = useTranslations("common")
  const [state, setState] = useState<LoadState>({ kind: "loading" })

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
      if (!v) setState({ kind: "loading" })
      onOpenChange(v)
    },
    [onOpenChange],
  )

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent
        className="max-w-[90vw] max-h-[90vh] w-[90vw] h-[90vh] flex flex-col p-0"
        showCloseButton={false}
      >
        {/* Header — 仅任务流名称 */}
        <DialogHeader className="shrink-0 px-6 pt-5 pb-0">
          <DialogTitle className="text-base">{workflowName}</DialogTitle>
        </DialogHeader>

        {/* Body */}
        <div className="flex-1 min-h-0 relative">
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
            />
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
