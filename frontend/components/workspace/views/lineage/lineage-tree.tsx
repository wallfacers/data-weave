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
import { LoadingState } from "@/components/workspace/shared/loading-state"
import {
  Database01Icon,
  ArrowDown01Icon,
  ArrowRight01Icon,
  Table01Icon,
  ColumnInsertIcon,
} from "@hugeicons/core-free-icons"
import type { GraphNodeView, NodeType } from "@/lib/lineage-api"
import { fetchDatasources, fetchColumns, fetchTablesByDatasource } from "@/lib/lineage-api"
import { DwScroll } from "@/components/ui/dw-scroll"

export interface TreeNode {
  id: string
  type: NodeType
  name: string
  layer?: string
  parentId?: string
  children: TreeNode[]
  expanded: boolean
  loaded: boolean
  loading: boolean
}

function iconForType(type: NodeType): IconSvgElement {
  return type === "DATASOURCE" ? Database01Icon : type === "COLUMN" ? ColumnInsertIcon : Table01Icon
}

export function LineageTree({
  onSelect,
}: {
  onSelect?: (node: GraphNodeView) => void
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

    if (node.expanded) {
      node.expanded = false
      setRoots(rootsCopy)
      return
    }

    if (!node.loaded) {
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
    node.expanded = true
    // 仅可锚定的 TABLE/COLUMN 触发选择（DATASOURCE 只作分组展开，不锚图）
    if (node.type === "TABLE" || node.type === "COLUMN") {
      onSelect?.({ id: node.id, type: node.type, name: node.name, layer: node.layer })
    }
    setRoots([...rootsCopy])
  }, [roots, onSelect])

  if (loading) return <LoadingState active={loading} />
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
          {node.loading ? (
            <span className="size-4 animate-spin text-muted-foreground">⟳</span>
          ) : node.expanded ? (
            <HugeiconsIcon icon={ArrowDown01Icon} className="size-4 text-muted-foreground shrink-0" />
          ) : (
            <HugeiconsIcon icon={ArrowRight01Icon} className="size-4 text-muted-foreground shrink-0" />
          )}
          <HugeiconsIcon icon={Icon} className="size-4 text-muted-foreground shrink-0" />
          <span className="truncate">{node.name}</span>
          {node.layer && (
            <span className="text-xs text-muted-foreground ml-auto shrink-0">{node.layer}</span>
          )}
        </button>
        {node.expanded && node.children.length > 0 && (
          <div>
            {node.children.map((child, i) => renderNode(child, depth + 1, [...path, i]))}
          </div>
        )}
        {node.expanded && node.loaded && node.children.length === 0 && (
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
