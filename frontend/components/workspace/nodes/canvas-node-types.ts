import type { Node } from "@xyflow/react"

/** 画布节点共享数据类型（workflow-canvas 与 dag-viewer-dialog 共用）。 */
export interface CanvasNodeData extends Record<string, unknown> {
  nodeType: "TASK" | "VIRTUAL"
  taskId: number | null
  label: string
  /** 运行态（来自事件流叠加，仅显示用，不入 DAG 草稿） */
  runState?: string
  /** 引用任务发布态（ONLINE/DRAFT）：非 ONLINE 即标「未发布」（ops-center-publish-boundary，仅显示用） */
  taskStatus?: string | null
}

export type CanvasNode = Node<CanvasNodeData>

/** 节点运行态 → 状态点颜色（语义 token）。 */
export function runStateDotClass(state?: string): string {
  switch (state) {
    case "SUCCESS":
      return "bg-success"
    case "RUNNING":
    case "DISPATCHED":
      return "bg-info animate-pulse"
    case "FAILED":
    case "KILLED":
    case "STOPPED":
      return "bg-destructive"
    case "WAITING":
    case "WAIT_RETRY":
      return "bg-warning animate-pulse"
    default:
      return "bg-muted-foreground/40"
  }
}
