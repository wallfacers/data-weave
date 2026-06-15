/**
 * Workspace 视图元数据（纯数据，无 React 依赖，可在 node 测试环境直接 import）。
 * 组件映射在 registry.tsx；store 只认这里的 viewType 集合。
 */

export type ViewType =
  | "cockpit"
  | "task-flow"
  | "workflow-canvas"
  | "freshness"
  | "reports"
  | "metrics"
  | "sql-workbench"
  | "diagnosis"
  | "fleet"
  | "instance-log"
  | "workflow-instance-detail"
  | "lineage"
  | "catalog"
  | "quality"
  | "integration"
  | "service"
  | "settings"

export interface ViewMeta {
  title: string
  /** true = Pinned 底座视图：恒定常驻、不可关闭、不进快照 */
  defaultPinned?: boolean
}

export const VIEW_META: Record<ViewType, ViewMeta> = {
  cockpit: { title: "驾驶舱", defaultPinned: true },
  "task-flow": { title: "任务流", defaultPinned: true },
  "workflow-canvas": { title: "工作流编排" },
  freshness: { title: "数据新鲜度", defaultPinned: true },
  reports: { title: "业务报表", defaultPinned: true },
  metrics: { title: "系统指标", defaultPinned: true },
  "sql-workbench": { title: "任务开发" },
  diagnosis: { title: "失败诊断" },
  fleet: { title: "集群机器" },
  "instance-log": { title: "实例日志" },
  "workflow-instance-detail": { title: "工作流实例" },
  lineage: { title: "数据血缘" },
  catalog: { title: "资产目录" },
  quality: { title: "数据质量" },
  integration: { title: "数据集成" },
  service: { title: "数据服务" },
  settings: { title: "系统设置" },
}

export const PINNED_VIEWS = (Object.keys(VIEW_META) as ViewType[]).filter(
  (v) => VIEW_META[v].defaultPinned,
)

export function isKnownView(view: string): view is ViewType {
  return view in VIEW_META
}
