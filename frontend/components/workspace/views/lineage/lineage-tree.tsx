"use client"

/**
 * 血缘三级树：数据源 → 表 → 列，受控展开折叠。
 *
 * 设计约束（DESIGN.md）：
 * - hugeicons 展开/折叠图标
 * - 语义 token，不手写 dark:
 * - gap-* / size-* 间距
 * - 无分割线
 */
import { useState, useCallback, useEffect } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon, type IconSvgElement } from "@hugeicons/react"
import {
  Database02Icon,
  ArrowDown01Icon,
  ArrowRight01Icon,
  GridTableIcon,
  LeftToRightListBulletIcon,
} from "@hugeicons/core-free-icons"
import type { GraphNodeView, NodeType } from "@/lib/lineage-api"
import { fetchDatasources, fetchColumns, fetchTablesByDatasource } from "@/lib/lineage-api"
import { DwScroll } from "@/components/ui/dw-scroll"

export interface TreeNode {
  id: string
  type: NodeType
  name: string
  layer?: string
  /** 数据源节点：其下表数量（后端 attrs.tableCount）——右侧显示，替代冗余的数据源名。 */
  tableCount?: number
  parentId?: string
  children: TreeNode[]
  expanded: boolean
  loaded: boolean
  loading: boolean
}

function iconForType(type: NodeType): IconSvgElement {
  return type === "DATASOURCE" ? Database02Icon : type === "COLUMN" ? LeftToRightListBulletIcon : GridTableIcon
}

export function LineageTree({
  onSelect,
  onSelectGroup,
}: {
  onSelect?: (node: GraphNodeView) => void
  /** 054：展开数据源 → 加载该数据源的一部分子图（其表集合的邻域并集）。 */
  onSelectGroup?: (tables: GraphNodeView[], label: string) => void
}) {
  const t = useTranslations("lineageView")
  const [roots, setRoots] = useState<TreeNode[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // 加载数据源
  const loadRoots = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const res = await fetchDatasources(0, 200)
      if (res.code !== 0) {
        // 后端返回错误码（包含 lineage.store_unavailable 等），统一降级显示
        setError(t("unavailable"))
        return
      }
      const nodes: TreeNode[] = (res.data ?? []).map((ds: GraphNodeView) => ({
        id: ds.id,
        type: ds.type,
        name: ds.name,
        layer: ds.layer,
        tableCount: typeof ds.attrs?.tableCount === "number" ? (ds.attrs.tableCount as number) : undefined,
        children: [],
        expanded: false,
        loaded: false,
        loading: false,
      }))
      setRoots(nodes)
    } catch {
      setError(t("unavailable"))
    } finally {
      setLoading(false)
    }
  }, [t])

  // 首次加载数据源（副作用在 useEffect 中执行，不在 render 体内）
  useEffect(() => {
    loadRoots()
  }, [loadRoots])

  // 懒加载子节点（054 US3：三级——数据源 → 表 → 列，沿 path 逐级下钻）
  const toggleExpand = useCallback(async (path: number[]) => {
    if (path.length === 0) return
    const rootsCopy = [...roots]
    let node: TreeNode | undefined = rootsCopy[path[0]]
    for (let i = 1; i < path.length && node; i++) node = node.children[path[i]]
    if (!node) return

    // 字段（列）是叶子：点击只锚图（列级血缘），不展开——避免其下出现无意义的「仅表级」空态
    if (node.type === "COLUMN") {
      onSelect?.({ id: node.id, type: node.type, name: node.name, layer: node.layer })
      return
    }

    const willExpand = !node.expanded
    // 展开且尚未加载 → 懒加载子节点（数据源→真实表；表→列）
    if (willExpand && !node.loaded) {
      node.loading = true
      setRoots([...rootsCopy])
      try {
        // 054 US3：三级树按节点类型下钻——数据源→真实表（fetchTablesByDatasource，修 052 占位 bug）；
        // 表→列（fetchColumns）。表节点 loaded=false 使其可继续展开出列。
        const res =
          node.type === "DATASOURCE"
            ? await fetchTablesByDatasource(node.id, 0, 200)
            : await fetchColumns(node.id, 0, 200)
        if (res.code === 0 && res.data) {
          node.children = res.data.map((child: GraphNodeView) => ({
            id: child.id,
            type: child.type,
            name: child.name,
            layer: child.layer,
            parentId: child.parentId,
            children: [],
            expanded: false,
            // 表节点仍可下钻列 → loaded=false；列节点无子 → loaded=true
            loaded: child.type === "COLUMN",
            loading: false,
          }))
          node.loaded = true
        }
      } catch {
        // 子节点加载失败 → silent，保持当前层级
      }
      node.loading = false
    }
    node.expanded = willExpand

    // 无论展开/折叠，点击即刷新右侧图（保证响应灵敏——此前折叠时 early-return 不刷图致「不灵敏」）
    if (node.type === "TABLE") {
      // 表 → 单点锚图
      onSelect?.({ id: node.id, type: node.type, name: node.name, layer: node.layer })
    } else if (node.type === "DATASOURCE" && node.children.length > 0) {
      // 数据源 → 加载其表集合的一部分子图（组视图）
      onSelectGroup?.(
        node.children.map((c) => ({ id: c.id, type: c.type, name: c.name, layer: c.layer })),
        node.name,
      )
    }
    setRoots([...rootsCopy])
  }, [roots, onSelect, onSelectGroup])

  if (loading) return <TreeSkeleton />
  if (error) return <div className="text-sm text-muted-foreground px-4 py-2">{error}</div>

  const renderNode = (node: TreeNode, depth: number, path: number[]) => {
    const Icon = iconForType(node.type)
    return (
      <div key={node.id}>
        <button
          className="flex items-center gap-1.5 w-full px-2 py-1.5 text-sm rounded-md
                     hover:bg-muted/50 transition-colors text-left"
          style={{ paddingLeft: `${8 + depth * 16}px` }}
          onClick={() => toggleExpand(path)}
        >
          {/* 列是叶子 → 占位对齐不显 chevron；表/数据源展开态用 chevron 直示（不显旋转 spinner，去抖动） */}
          {node.type === "COLUMN" ? (
            <span className="size-4 shrink-0" aria-hidden />
          ) : (
            <HugeiconsIcon
              icon={node.expanded ? ArrowDown01Icon : ArrowRight01Icon}
              className="size-4 text-muted-foreground shrink-0"
            />
          )}
          <HugeiconsIcon icon={Icon} className="size-4 text-muted-foreground shrink-0" />
          <span className="truncate">{node.name}</span>
          {/* 右侧标注：数据源 → 表数量；其它（表/列）→ 明确的所属层级 */}
          {node.type === "DATASOURCE"
            ? node.tableCount != null && (
                <span className="ml-auto shrink-0 text-xs tabular-nums text-muted-foreground">
                  {node.tableCount}
                </span>
              )
            : node.layer && (
                <span className="ml-auto shrink-0 text-xs text-muted-foreground">{node.layer}</span>
              )}
        </button>
        {node.expanded && node.children.length > 0 && (
          <div>
            {node.children.map((child, i) => renderNode(child, depth + 1, [...path, i]))}
          </div>
        )}
        {/* 空态「仅表级」仅在表节点展开却无列时提示；数据源/列节点不显（列已作叶子不展开） */}
        {node.type === "TABLE" && node.expanded && node.loaded && node.children.length === 0 && (
          <div
            className="text-xs text-muted-foreground py-1"
            style={{ paddingLeft: `${24 + (depth + 1) * 16}px` }}
          >
            {t("noColumns")}
          </div>
        )}
      </div>
    )
  }

  return (
    <DwScroll className="h-full" innerClassName="py-1">
      {roots.length === 0 && !loading && (
        <div className="text-sm text-muted-foreground px-4 py-8 text-center">{t("empty")}</div>
      )}
      {roots.map((root, i) => renderNode(root, 0, [i]))}
    </DwScroll>
  )
}

/**
 * 树骨架占位：打开/刷新数据源树时安静显示，替代整屏旋转 spinner。
 * 沿用仓库骨架惯例（bg-muted + animate-pulse），条宽错落模拟树行，不喧宾夺主。
 */
function TreeSkeleton() {
  const widths = [64, 52, 58, 46, 60, 50]
  return (
    <div className="flex flex-col gap-2 px-2 py-2" aria-hidden>
      {widths.map((w, i) => (
        <div key={i} className="flex items-center gap-1.5 px-2 py-0.5">
          <div className="bg-muted size-4 shrink-0 animate-pulse rounded" />
          <div className="bg-muted h-3.5 animate-pulse rounded-md" style={{ width: `${w}%` }} />
        </div>
      ))}
    </div>
  )
}
