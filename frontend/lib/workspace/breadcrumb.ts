/**
 * 面包屑路径派生（纯函数，无 React 依赖，可在 node 测试环境直接 import）。
 *
 * 数据源全部复用 032 已交付的既有数据：
 * - 分组标题：resolveActiveHighlight / NAV_GROUPS（来自 ./nav-groups）
 * - 视图标题：VIEW_META（来自 ./views）
 * - 动态参数值：复用 tab-bar.tsx 的 tabLabel "首个 param 值"约定
 */
import { resolveActiveHighlight, NAV_GROUPS } from "./nav-groups"
import { VIEW_META, type ViewType } from "./views"

export interface BreadcrumbNode {
  label: string
}

/**
 * 根据当前视图与参数，派生出面包屑节点序列。
 *
 * 序列结构：项目名 → 分组标题(可选) → 视图标题 → 动态参数(可选)
 * - 分组缺失时序列退化为二级（项目 → 视图），不返回空节点、不抛错。
 * - 动态参数级复用 tab-bar.tsx 的 `tabLabel` 同款"首个 param 值"约定。
 */
export function deriveBreadcrumbNodes(
  view: ViewType,
  params: Record<string, unknown> | undefined,
  projectName: string,
  t: (k: string) => string,
): BreadcrumbNode[] {
  const nodes: BreadcrumbNode[] = []

  // 项目节点（始终存在）
  nodes.push({ label: projectName })

  // 分组节点（可选）：resolveActiveHighlight 返回 group 时追加
  // titleKey 存储格式为 "groups.<id>"，完整 i18n key 为 "leftNav.groups.<id>"
  const highlight = resolveActiveHighlight(view)
  if (highlight.group) {
    const group = NAV_GROUPS.find((g) => g.id === highlight.group)
    if (group) {
      nodes.push({ label: t(`leftNav.${group.titleKey}`) })
    }
  }

  // 视图节点（始终存在）
  nodes.push({ label: t(VIEW_META[view].title) })

  // 动态参数节点（可选）：复用 tabLabel 的"首个 param 值"约定
  if (params) {
    const first = Object.values(params)[0]
    if (first != null) {
      nodes.push({ label: String(first) })
    }
  }

  return nodes
}
