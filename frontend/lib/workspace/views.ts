/**
 * Workspace 视图元数据（纯数据，无 React 依赖，可在 node 测试环境直接 import）。
 * 组件映射在 registry.tsx；store 只认这里的 viewType 集合。
 *
 * `title` 为 i18n key（命名空间 `views.<viewType>`）；渲染侧用 `useTranslations()` 解析，
 * 避免本文件引入 React 依赖。
 */

export type ViewType =
  | "ops"
  | "workflow-canvas"
  | "freshness"
  | "reports"
  | "metrics"
  | "fleet"
  | "lineage"
  | "catalog"
  | "marketplace"
  | "quality"
  | "integration"
  | "datasources"
  | "service"
  | "alerts"
  | "event-center"
  | "settings"
  | "instance-log"
  | "workflow-instance-detail"

export interface ViewMeta {
  /** i18n key（命名空间 `views.*`）；渲染侧经 `useTranslations("views")` 解析为本地化字符串 */
  title: string
  /** true = Pinned 底座视图：恒定常驻、不可关闭、不进快照 */
  defaultPinned?: boolean
  /**
   * 036-D：打开/查看该视图所需的项目权限 code（如 "workflow:manage"）。
   * 缺省（undefined）= 所有项目角色（含 VIEWER）可见的只读视图。
   * 左侧导航与视图渲染层据此过滤：无权限的菜单不渲染、无权限的视图不可直达（FR-041）。
   */
  requirePermission?: string
}

export const VIEW_META: Record<ViewType, ViewMeta> = {
  ops: { title: "views.ops" },
  "workflow-canvas": { title: "views.workflowCanvas", requirePermission: "workflow:manage" },
  freshness: { title: "views.freshness", defaultPinned: true },
  reports: { title: "views.reports", defaultPinned: true },
  metrics: { title: "views.metrics", defaultPinned: true },
  fleet: { title: "views.fleet" },
  lineage: { title: "views.lineage" },
  catalog: { title: "views.catalog", requirePermission: "task:manage" },
  marketplace: { title: "views.marketplace", requirePermission: "metric:manage" },
  quality: { title: "views.quality" },
  integration: { title: "views.integration" },
  datasources: { title: "views.datasources", requirePermission: "datasource:manage" },
  alerts: { title: "views.alerts" },
  "event-center": { title: "views.eventCenter" },
  service: { title: "views.service" },
  settings: { title: "views.settings", requirePermission: "project:manage" },
  "instance-log": { title: "views.instanceLog" },
  "workflow-instance-detail": { title: "views.workflowInstanceDetail" },
}

export const PINNED_VIEWS = (Object.keys(VIEW_META) as ViewType[]).filter(
  (v) => VIEW_META[v].defaultPinned,
)

export function isKnownView(view: string): view is ViewType {
  return view in VIEW_META
}
