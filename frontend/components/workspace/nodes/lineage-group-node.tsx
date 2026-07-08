"use client"

/**
 * 054 US4：数据源分组泳道容器节点。
 *
 * 仅作背景容器 + 顶部数据源标签（徽标色点 + 缩写 + 名 + 成员数），成员节点由 ReactFlow
 * 按 parentId 定位在其内。容器自身不可交互（pointer-events 由布局层 style 关掉，空白处点击
 * 落到画布 pane），故不干扰成员点击与「点空白关面板」。
 *
 * 设计约束（DESIGN.md）：语义 token、size-*、不手写 dark:。
 */
import { type NodeProps } from "@xyflow/react"
import { datasourceColor, datasourceAbbr } from "@/lib/workspace/lineage-datasource-style"
import type { LineageNode } from "./lineage-node-types"

export function LineageGroupNode({ data }: NodeProps<LineageNode>) {
  const d = data
  const dsName = d.datasourceName ?? d.name
  const count = typeof d.count === "number" ? d.count : undefined
  return (
    <div className="pointer-events-none relative size-full rounded-lg border border-dashed border-border/70 bg-muted/20">
      <div className="absolute left-2 top-1.5 flex items-center gap-1.5 rounded-md bg-card/90 px-2 py-1 text-[11px] shadow-sm backdrop-blur-sm">
        <span className="size-2 rounded-full" style={{ backgroundColor: datasourceColor(d.datasourceId) }} aria-hidden />
        <span className="font-medium">{datasourceAbbr(dsName)}</span>
        <span className="max-w-[140px] truncate text-muted-foreground">{dsName}</span>
        {count != null && <span className="text-muted-foreground/70">· {count}</span>}
      </div>
    </div>
  )
}
