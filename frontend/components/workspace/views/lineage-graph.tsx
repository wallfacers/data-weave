"use client"

import { useMemo } from "react"
import { useTranslations } from "next-intl"
import {
  ReactFlow,
  ReactFlowProvider,
  Background,
  Controls,
  Handle,
  Position,
  type Node,
  type Edge,
  type NodeProps,
} from "@xyflow/react"
import "@xyflow/react/dist/style.css"
import { HugeiconsIcon } from "@hugeicons/react"
import { DatabaseIcon } from "@hugeicons/core-free-icons"

import { useApi } from "@/lib/workspace/use-api"
import { ViewStatus } from "./view-status"

// ─── 后端 LineageGraph 对应类型 ─────────────────────────────

interface LineageNode {
  id: number
  datasourceId: number
  qualifiedName: string
  layer: string | null
}
interface LineageFlowEdge {
  fromTableId: number
  toTableId: number
  taskDefId: number
  confidence: string
}
interface LineageGraphData {
  nodes: LineageNode[]
  edges: LineageFlowEdge[]
}

// 分层列顺序：未分层(源)在最左，再 ODS→DWD→DWS→ADS
const LAYER_ORDER = ["SOURCE", "ODS", "DWD", "DWS", "ADS"]
const COL_W = 230
const ROW_H = 92

const LAYER_TONE: Record<string, string> = {
  SOURCE: "border-muted-foreground/30 bg-muted/40 text-muted-foreground",
  ODS: "border-muted-foreground/30 bg-muted/40 text-foreground",
  DWD: "border-info/40 bg-info/10 text-info",
  DWS: "border-primary/40 bg-primary/10 text-primary",
  ADS: "border-success/40 bg-success/10 text-success",
}

type LineageNodeData = { label: string; layer: string }

/** 血缘图自定义节点：表名 + layer 徽标，左进右出。 */
function TableNode({ data }: NodeProps) {
  const d = data as LineageNodeData
  const tone = LAYER_TONE[d.layer] ?? LAYER_TONE.SOURCE
  return (
    <div className={`flex items-center gap-2 rounded-lg border px-3 py-2 shadow-sm ${tone}`}>
      <Handle type="target" position={Position.Left} className="!bg-muted-foreground/50" />
      <HugeiconsIcon icon={DatabaseIcon} className="size-4 shrink-0" />
      <div className="flex flex-col min-w-0">
        <span className="truncate text-sm font-medium font-sans">{d.label}</span>
        <span className="text-[10px] uppercase tracking-wide opacity-70">{d.layer}</span>
      </div>
      <Handle type="source" position={Position.Right} className="!bg-muted-foreground/50" />
    </div>
  )
}

const NODE_TYPES = { table: TableNode }

function layerRank(layer: string | null): number {
  const key = layer && LAYER_ORDER.includes(layer) ? layer : "SOURCE"
  return LAYER_ORDER.indexOf(key)
}

/** 把后端图映射为 ReactFlow 节点/边，按 layer 分列布局，列内纵向堆叠。 */
function toFlow(graph: LineageGraphData): { nodes: Node[]; edges: Edge[] } {
  const colCounts: Record<number, number> = {}
  const nodes: Node[] = graph.nodes.map((n) => {
    const col = layerRank(n.layer)
    const row = colCounts[col] ?? 0
    colCounts[col] = row + 1
    return {
      id: String(n.id),
      type: "table",
      position: { x: col * COL_W, y: row * ROW_H },
      data: { label: n.qualifiedName, layer: n.layer ?? "SOURCE" } satisfies LineageNodeData,
      draggable: false,
    }
  })
  const edges: Edge[] = graph.edges.map((e, i) => {
    const conflict = e.confidence === "CONFLICT"
    const unverified = e.confidence === "UNVERIFIED"
    const color = conflict
      ? "var(--color-destructive)"
      : unverified
        ? "var(--color-warning)"
        : "var(--color-muted-foreground)"
    return {
      id: `e${i}-${e.fromTableId}-${e.toTableId}`,
      source: String(e.fromTableId),
      target: String(e.toTableId),
      animated: !conflict && !unverified,
      style: {
        stroke: color,
        strokeWidth: 1.5,
        strokeDasharray: conflict || unverified ? "5 4" : undefined,
      },
    }
  })
  return { nodes, edges }
}

function LegendDot({ className, label }: { className: string; label: string }) {
  return (
    <div className="flex items-center gap-1.5">
      <span className={`inline-block h-0.5 w-4 ${className}`} />
      <span className="text-[11px] text-muted-foreground">{label}</span>
    </div>
  )
}

function LineageGraphInner() {
  const t = useTranslations("lineageCockpit")
  const { data: graph, loading } = useApi<LineageGraphData>("/api/lineage/graph")

  const flow = useMemo(() => (graph ? toFlow(graph) : { nodes: [], edges: [] }), [graph])

  if (!graph) return <ViewStatus loading={loading} />
  if (graph.nodes.length === 0) {
    return (
      <div className="flex flex-1 flex-col items-center justify-center gap-2 p-8 text-center">
        <p className="text-sm text-muted-foreground">{t("stagePending")}</p>
      </div>
    )
  }

  return (
    <div className="relative flex-1">
      <ReactFlow
        nodes={flow.nodes}
        edges={flow.edges}
        nodeTypes={NODE_TYPES}
        nodesDraggable={false}
        nodesConnectable={false}
        fitView
        fitViewOptions={{ padding: 0.2 }}
        proOptions={{ hideAttribution: true }}
      >
        <Background />
        <Controls showInteractive={false} />
      </ReactFlow>
      {/* 可信度图例 */}
      <div className="absolute right-3 top-3 z-10 flex flex-col gap-1.5 rounded-lg border bg-card/90 px-3 py-2 backdrop-blur">
        <LegendDot className="bg-muted-foreground" label={t("legendConfirmed")} />
        <LegendDot className="bg-warning" label={t("legendUnverified")} />
        <LegendDot className="bg-destructive" label={t("legendConflict")} />
      </div>
    </div>
  )
}

/** 跨系统活血缘图（ReactFlowProvider 自带）。 */
export function LineageGraph() {
  return (
    <ReactFlowProvider>
      <LineageGraphInner />
    </ReactFlowProvider>
  )
}
