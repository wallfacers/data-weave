import type { Node } from "@xyflow/react"
import type { Granularity, LineageColumnItem, NodeType } from "@/lib/lineage-api"

/**
 * 血缘画布节点数据（与工作流 CanvasNodeData 分离，research D3）。
 *
 * 语义：层色 / 新鲜度 / synced rows / 产出任务 / 可展开列 / 锚点 / 受影响 / 暗淡。
 * 仅承载可序列化的展示字段；动作回调经 LineageNodeActionsContext 注入（不入 data）。
 */
export interface LineageNodeData extends Record<string, unknown> {
  /** 节点领域类型（ReactFlow 的 node.type 也用它，但 data 内冗余便于渲染分支）。 */
  nodeType: NodeType
  /** 显示名（Table=qualifiedName / Column=name / Metric=name / Datasource=name）。 */
  name: string
  /** 所属层（ODS/DWD/DWS/ADS/DIM…），驱动层色点缀。 */
  layer?: string
  /** 粒度（表级视图下多数为 TABLE；列级展开的列节点为 COLUMN）。 */
  granularity?: Granularity
  /** 最近同步业务日期（yyyy-MM-dd），驱动新鲜度展示。 */
  lastSyncDate?: string
  /** 今日同步行数（null = 无同步记录）。 */
  syncedRowsToday?: number | null
  /** 产出任务名列表。 */
  producers?: string[]
  /** 054：所属数据源 id（徽标配色种子 + 跨源判定；孤儿/METRIC 缺省）。 */
  datasourceId?: string
  /** 054：数据源展示名（徽标显示）。 */
  datasourceName?: string
  /** 列级展开：该表拥有的列数（>0 才显示展开按钮）。 */
  columnCount?: number
  /** 是否当前锚点（居中高亮）。 */
  isAnchor?: boolean
  /** 是否在影响/路径高亮集内。 */
  isImpacted?: boolean
  /** 是否已展开列。 */
  expanded?: boolean
  /** 展开态内联列清单（chevron 展开，FR-015）。 */
  columns?: LineageColumnItem[]
  /** 焦点态（选中/影响/路径）下，非相关节点置暗。 */
  dimmed?: boolean
}

export type LineageNode = Node<LineageNodeData>

/** 层 → chart categorical 色点缀（ODS→1 / DWD→2 / DWS→3 / ADS→4 / DIM→5）。 */
export function layerDotClass(layer?: string): string {
  switch ((layer ?? "").toUpperCase()) {
    case "ODS":
      return "bg-chart-1"
    case "DWD":
      return "bg-chart-2"
    case "DWS":
      return "bg-chart-3"
    case "ADS":
      return "bg-chart-4"
    case "DIM":
    case "DIMENSION":
      return "bg-chart-5"
    default:
      return "bg-muted-foreground/40"
  }
}
