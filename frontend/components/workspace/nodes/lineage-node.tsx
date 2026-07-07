"use client"

/**
 * 血缘节点组件（DATASOURCE / TABLE / COLUMN / METRIC）。
 *
 * 与工作流 TaskNode 分离（语义不同：层色/新鲜度/synced/可展开列，research D3）。
 * 紧凑卡片：名称 + 层色点缀 + 类型图标 + 可选新鲜度行 + 展开列 chevron。
 * 富属性（产出任务/详细新鲜度/synced）在详情面板呈现（FR-019 节点/详情均可读）。
 *
 * Handle 左=target / 右=source 契合上游左→下游右（rankdir=LR）。
 * 设计约束（DESIGN.md）：语义 token、层色取 chart categorical 功能色、不手写 dark:。
 */
import { useTranslations } from "next-intl"
import { Handle, Position, type NodeProps } from "@xyflow/react"
import { HugeiconsIcon, type IconSvgElement } from "@hugeicons/react"
import {
  Activity02Icon,
  AnchorIcon,
  ArrowDown01Icon,
  ArrowRight01Icon,
  ColumnInsertIcon,
  Database01Icon,
  Table01Icon,
} from "@hugeicons/core-free-icons"
import { cn } from "@/lib/utils"
import type { NodeType } from "@/lib/lineage-api"
import { type LineageNode, layerDotClass } from "./lineage-node-types"
import { useLineageNodeActions } from "./lineage-node-actions-context"

function iconForType(type: NodeType): IconSvgElement {
  switch (type) {
    case "COLUMN":
      return ColumnInsertIcon
    case "DATASOURCE":
      return Database01Icon
    case "METRIC":
      return Activity02Icon
    default:
      return Table01Icon
  }
}

/** 紧凑新鲜度提示：今日 synced rows（有则显示），否则最近同步日期。 */
function FreshnessHint({ synced, lastSyncDate }: { synced?: number | null; lastSyncDate?: string }) {
  if (synced == null && !lastSyncDate) return null
  const text = synced != null ? `${synced.toLocaleString()} rows` : lastSyncDate
  return <span className="truncate text-[10px] text-muted-foreground">{text}</span>
}

export function LineageNode({ id, data }: NodeProps<LineageNode>) {
  const t = useTranslations("lineageView")
  const actions = useLineageNodeActions()
  const d = data
  const isTable = d.nodeType === "TABLE"
  const showExpand = isTable && (d.columnCount ?? 0) > 0
  const narrow = d.nodeType === "COLUMN"

  return (
    <div
      className={cn(
        "relative rounded-md border bg-card px-2.5 py-1.5 text-xs shadow-sm transition-colors",
        narrow ? "w-[168px]" : "w-[200px]",
        d.isAnchor && "border-primary ring-2 ring-primary",
        !d.isAnchor && d.isImpacted && "border-primary bg-primary/5",
        !d.isAnchor && !d.isImpacted && "border-border",
        d.dimmed && "opacity-40",
      )}
    >
      <Handle type="target" position={Position.Left} />

      {/* 锚点标记 */}
      {d.isAnchor && (
        <span
          className="absolute -right-1.5 -top-1.5 flex size-4 items-center justify-center rounded-full bg-primary text-primary-foreground ring-2 ring-card"
          title={t("anchor")}
        >
          <HugeiconsIcon icon={AnchorIcon} className="size-2.5" />
        </span>
      )}

      {/* 头部：层色 + 类型图标 + 名称 + 展开 */}
      <div className="flex items-center gap-1.5">
        <span className={cn("size-2 shrink-0 rounded-full", layerDotClass(d.layer))} />
        <HugeiconsIcon icon={iconForType(d.nodeType)} className="size-3.5 shrink-0 text-muted-foreground" />
        <span className="min-w-0 flex-1 truncate font-medium">{d.name}</span>
        {showExpand && (
          <button
            type="button"
            aria-label={d.expanded ? t("collapse") : t("expand")}
            onClick={(e) => {
              e.stopPropagation()
              actions?.onToggleExpand?.(id)
            }}
            className="-mr-1 flex size-5 shrink-0 items-center justify-center rounded text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
          >
            <HugeiconsIcon icon={d.expanded ? ArrowDown01Icon : ArrowRight01Icon} className="size-3.5" />
          </button>
        )}
      </div>

      {/* 元信息：层 + 新鲜度（紧凑一行，列节点不显示） */}
      {!narrow && (d.layer || d.syncedRowsToday != null || d.lastSyncDate) && (
        <div className="mt-0.5 flex items-center gap-1.5 pl-[22px]">
          {d.layer && (
            <span className="shrink-0 rounded bg-muted px-1 py-0.5 text-[10px] leading-none text-muted-foreground">
              {d.layer}
            </span>
          )}
          <FreshnessHint synced={d.syncedRowsToday} lastSyncDate={d.lastSyncDate} />
        </div>
      )}

      <Handle type="source" position={Position.Right} />
    </div>
  )
}

/** 血缘 nodeTypes 注册（datasource/table/column/metric 统一走 LineageNode）。 */
export const lineageNodeTypes = {
  datasource: LineageNode,
  table: LineageNode,
  column: LineageNode,
  metric: LineageNode,
}
