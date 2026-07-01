/**
 * 左侧导航的功能模块分组（纯数据，无 React 依赖，可在 node 测试环境直接 import）。
 *
 * 分组标题为 i18n key（命名空间 `leftNav.groups.<id>`）；功能项名称复用 `views.<viewType>`
 * （见 VIEW_META.title），图标复用 `registry.tsx` 的 VIEW_RENDER[view].icon。
 *
 * 不变量（见 nav-groups.test.ts）：所有入口视图 ∪ 上下文详情视图 == VIEW_META 全集，且入口无重复。
 * 新增视图：在下面对应分组的 items 加一行（或归入详情视图）。
 */
import { VIEW_META, type ViewType } from "./views"

export interface NavGroup {
  /** 分组稳定标识 */
  id: string
  /** i18n key（`leftNav.groups.<id>`） */
  titleKey: string
  /** 该分组下有序的入口功能视图 */
  items: ViewType[]
}

/** 有序分组目录（决定导航显示顺序，FR-005）。 */
export const NAV_GROUPS: NavGroup[] = [
  { id: "dev", titleKey: "groups.dev", items: ["workflow-canvas"] },
  { id: "ops", titleKey: "groups.ops", items: ["ops", "metrics", "fleet", "freshness"] },
  { id: "alerting", titleKey: "groups.alerting", items: ["alerts", "event-center"] },
  { id: "governance", titleKey: "groups.governance", items: ["catalog", "quality", "lineage"] },
  { id: "assets", titleKey: "groups.assets", items: ["marketplace", "datasources", "integration", "service"] },
  { id: "analytics", titleKey: "groups.analytics", items: ["reports"] },
  { id: "admin", titleKey: "groups.admin", items: ["settings"] },
]

/**
 * 上下文详情视图：只能携带参数从其他视图跳转打开，不作为独立导航入口（FR-007）。
 */
export const CONTEXT_DETAIL_VIEWS: ReadonlySet<ViewType> = new Set<ViewType>([
  "instance-log",
  "workflow-instance-detail",
])

/** 所有入口功能视图集合（导航中可点击的项）。 */
export const NAV_ENTRY_VIEWS: ReadonlySet<ViewType> = new Set<ViewType>(
  NAV_GROUPS.flatMap((g) => g.items),
)

/** 入口视图 → 所属 groupId 反查表（构建期生成）。 */
export const viewToGroup: Partial<Record<ViewType, string>> = Object.fromEntries(
  NAV_GROUPS.flatMap((g) => g.items.map((v) => [v, g.id] as const)),
)

/**
 * 上下文详情视图 → 高亮归属（父功能/模块）（FR-007/D5）。
 * 两个详情视图均归属运维监控模块。
 */
export const detailViewParent: Partial<Record<ViewType, { view?: ViewType; group: string }>> = {
  "instance-log": { group: "ops" },
  "workflow-instance-detail": { group: "ops" },
}

/**
 * 解析当前激活视图的导航高亮归属。
 * - 入口视图 → 高亮自身 + 所属分组
 * - 上下文详情视图 → 归父模块（无具体高亮项）
 * - 未知/无法归属 → 无高亮
 */
export function resolveActiveHighlight(
  view: ViewType | undefined,
): { view?: ViewType; group?: string } {
  if (!view) return {}
  if (NAV_ENTRY_VIEWS.has(view)) return { view, group: viewToGroup[view] }
  const parent = detailViewParent[view]
  if (parent) return { view: parent.view, group: parent.group }
  return {}
}

/** 校验辅助：返回所有已归类（入口 ∪ 详情）视图集合，供测试断言覆盖 VIEW_META 全集。 */
export function classifiedViews(): Set<ViewType> {
  const s = new Set<ViewType>(NAV_ENTRY_VIEWS)
  for (const v of CONTEXT_DETAIL_VIEWS) s.add(v)
  return s
}

/** VIEW_META 全集（测试用）。 */
export const ALL_VIEWS = Object.keys(VIEW_META) as ViewType[]
