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
import { useState, useCallback } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon, type IconSvgElement } from "@hugeicons/react"
import {
  Database01Icon,
  ArrowDown01Icon,
  ArrowRight01Icon,
  Table01Icon,
  ColumnInsertIcon,
} from "@hugeicons/core-free-icons"
import type { GraphNodeView, NodeType } from "@/lib/lineage-api"
import { fetchDatasources, fetchColumns } from "@/lib/lineage-api"
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
    if (roots.length > 0) return
    setLoading(true)
    setError(null)
    try {
      const res = await fetchDatasources(0, 200)
      if (res.code === "lineage.store_unavailable") {
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
  }, [roots.length, t])

  // 懒加载子节点（展开数据源 → 表；展开表 → 列）
  const toggleExpand = useCallback(async (path: number[]) => {
    if (path.length === 0) return
    // 简单实现：仅支持两级（root→table→column）
    const rootIdx = path[0]
    const rootsCopy = [...roots]
    const node = path.length === 1 ? rootsCopy[rootIdx] : rootsCopy[rootIdx]?.children[path[1]]
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
        // 数据源 → 加载表       实际从 datasources 接口返回后需扩展：这里简化——点击 datasource 加载 columns（临时）
        // 正式实现需 018/019 提供 tables-by-datasource 端点。当前用占位逻辑。
        const res = await fetchColumns(node.id, 0, 200)
        if (res.code !== "lineage.store_unavailable" && res.data) {
          node.children = res.data.map((col: GraphNodeView) => ({
            id: col.id,
            type: col.type,
            name: col.name,
            layer: col.layer,
            parentId: col.parentId,
            children: [],
            expanded: false,
            loaded: true,
            loading: false,
          }))
          node.loaded = true
        }
      } catch {
        // 列加载失败 → silent，保持"仅表级"
      }
      node.loading = false
    }
    node.expanded = true
    onSelect?.({ id: node.id, type: node.type, name: node.name, layer: node.layer })
    setRoots([...rootsCopy])
  }, [roots, onSelect])

  // 首次渲染加载
  if (roots.length === 0 && !loading && !error) {
    loadRoots()
  }

  if (loading) return <div className="text-sm text-muted-foreground px-4 py-2">{t("loading")}</div>
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
