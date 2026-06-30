"use client"

/**
 * 血缘流画布（自绘 SVG 折线）。
 *
 * 设计约束（DESIGN.md）：
 * - 语义 token（bg-primary/10 高亮、ring 选中）
 * - 不手写 dark:
 * - 无分割线
 */
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  Table01Icon,
  ColumnInsertIcon,
  ArrowRight02Icon,
} from "@hugeicons/core-free-icons"
import type { GraphNodeView, FlowEdgeView, Granularity } from "@/lib/lineage-api"

interface Props {
  nodes: GraphNodeView[]
  edges: FlowEdgeView[]
  granularity: Granularity
  selectedId?: string
  impactedIds?: Set<string>
  truncated?: boolean
  onSelect?: (node: GraphNodeView) => void
  onToggleGranularity?: () => void
}

export function LineageFlow({
  nodes,
  edges,
  granularity,
  selectedId,
  impactedIds,
  truncated,
  onSelect,
  onToggleGranularity,
}: Props) {
  const t = useTranslations("lineageView")

  if (nodes.length === 0) {
    return (
      <div className="flex items-center justify-center h-full">
        <div className="text-sm text-muted-foreground">
          {t("empty")}
        </div>
      </div>
    )
  }

  // 简单网格布局：每行最多 4 个节点
  const COLS = 4
  const CELL_W = 180
  const CELL_H = 64
  const GAP_X = 40
  const GAP_Y = 16

  const rows = Math.ceil(nodes.length / COLS)
  const svgW = COLS * CELL_W + (COLS - 1) * GAP_X + 40
  const svgH = rows * CELL_H + (rows - 1) * GAP_Y + 40

  return (
    <div className="flex flex-col h-full gap-2">
      {/* Granularity toggle */}
      <div className="flex items-center gap-2 px-3 pt-2 shrink-0">
        <button
          className={`text-xs px-2 py-1 rounded-md transition-colors ${
            granularity === "TABLE"
              ? "bg-primary text-primary-foreground"
              : "bg-muted text-muted-foreground hover:bg-muted/70"
          }`}
          onClick={onToggleGranularity}
        >
          {t("granularityTable")}
        </button>
        <button
          className={`text-xs px-2 py-1 rounded-md transition-colors ${
            granularity === "COLUMN"
              ? "bg-primary text-primary-foreground"
              : "bg-muted text-muted-foreground hover:bg-muted/70"
          }`}
          onClick={onToggleGranularity}
        >
          {t("granularityColumn")}
        </button>
        {truncated && (
          <span className="text-xs text-muted-foreground ml-auto">{t("truncated")}</span>
        )}
      </div>

      {/* SVG Canvas */}
      <div className="flex-1 overflow-auto">
        <svg
          width={svgW}
          height={svgH}
          className="min-w-full"
          style={{ minHeight: svgH }}
        >
          {/* Edges */}
          {edges.map((edge, i) => {
            const fromIdx = nodes.findIndex((n) => n.id === edge.from)
            const toIdx = nodes.findIndex((n) => n.id === edge.to)
            if (fromIdx < 0 || toIdx < 0) return null
            const x1 = 20 + (fromIdx % COLS) * (CELL_W + GAP_X) + CELL_W
            const y1 = 20 + Math.floor(fromIdx / COLS) * (CELL_H + GAP_Y) + CELL_H / 2
            const x2 = 20 + (toIdx % COLS) * (CELL_W + GAP_X)
            const y2 = 20 + Math.floor(toIdx / COLS) * (CELL_H + GAP_Y) + CELL_H / 2
            const midX = (x1 + x2) / 2
            const isImpacted =
              impactedIds?.has(edge.to) || impactedIds?.has(edge.from)
            return (
              <polyline
                key={i}
                points={`${x1},${y1} ${midX},${y1} ${midX},${y2} ${x2},${y2}`}
                fill="none"
                stroke={isImpacted ? "var(--color-primary)" : "var(--color-border)"}
                strokeWidth={isImpacted ? 2 : 1}
                strokeDasharray={edge.confidence === "UNVERIFIED" ? "4 2" : undefined}
                opacity={isImpacted ? 0.8 : 0.4}
              />
            )
          })}

          {/* Nodes */}
          {nodes.map((node, i) => {
            const x = 20 + (i % COLS) * (CELL_W + GAP_X)
            const y = 20 + Math.floor(i / COLS) * (CELL_H + GAP_Y)
            const isSelected = node.id === selectedId
            const isImpacted = impactedIds?.has(node.id)
            const Icon = node.type === "COLUMN" ? ColumnInsertIcon : Table01Icon
            return (
              <g
                key={node.id}
                transform={`translate(${x},${y})`}
                className="cursor-pointer"
                onClick={() => onSelect?.(node)}
              >
                <rect
                  width={CELL_W}
                  height={CELL_H}
                  rx={8}
                  fill={
                    isSelected
                      ? "var(--color-primary)"
                      : isImpacted
                        ? "var(--color-primary)"
                      : "var(--color-card)"
                  }
                  fillOpacity={isSelected ? 1 : isImpacted ? 0.1 : 1}
                  stroke={
                    isSelected
                      ? "var(--color-primary)"
                      : isImpacted
                        ? "var(--color-primary)"
                        : "var(--color-border)"
                  }
                  strokeWidth={isSelected || isImpacted ? 2 : 1}
                />
                <foreignObject width={CELL_W} height={CELL_H}>
                  <div
                    className="flex items-center gap-2 px-3 h-full"
                    style={{
                      color: isSelected
                        ? "var(--color-primary-foreground)"
                        : "var(--color-foreground)",
                    }}
                  >
                    <HugeiconsIcon icon={Icon} className="size-5 shrink-0" />
                    <div className="min-w-0">
                      <div className="text-xs font-medium truncate">{node.name}</div>
                      {node.layer && (
                        <div className="text-[10px] opacity-70">{node.layer}</div>
                      )}
                    </div>
                  </div>
                </foreignObject>
              </g>
            )
          })}
        </svg>
      </div>

      {/* Legend */}
      <div className="flex items-center gap-4 px-3 pb-2 text-xs text-muted-foreground shrink-0">
        <span className="flex items-center gap-1">
          <span className="size-2 rounded-full bg-border" />
          {edges.length} {edges.length === 1 ? "edge" : "edges"}
        </span>
        <span className="flex items-center gap-1">
          <HugeiconsIcon icon={ArrowRight02Icon} className="size-3" />
          {granularity === "TABLE" ? t("granularityTable") : t("granularityColumn")}
        </span>
      </div>
    </div>
  )
}
