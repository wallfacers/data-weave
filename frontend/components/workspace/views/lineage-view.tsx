"use client"

/**
 * 企业级血缘视图 —— 020 多粒度血缘浏览与下钻。
 *
 * 架构：左侧三级树（数据源→表→列）+ 右侧血缘流画布。
 * 设计约束（DESIGN.md）：
 * - 无 header/footer 分割线，区域靠留白
 * - 语义 token，不手写 dark:
 * - gap-* / size-* 间距
 * - hugeicons 图标
 */
import { useState, useCallback } from "react"
import { useTranslations } from "next-intl"
import type { GraphNodeView, FlowEdgeView, Granularity, ImpactResult } from "@/lib/lineage-api"
import {
  fetchDownstream,
  fetchColumnDownstream,
  fetchImpact,
} from "@/lib/lineage-api"
import { LineageTree } from "./lineage/lineage-tree"
import { LineageFlow } from "./lineage/lineage-flow"
import { ImpactPanel } from "./lineage/impact-panel"

export function LineageView() {
  const t = useTranslations("lineageView")

  // 选中节点
  const [selectedNode, setSelectedNode] = useState<GraphNodeView | null>(null)
  const [granularity, setGranularity] = useState<Granularity>("TABLE")

  // 血缘流数据
  const [nodes, setNodes] = useState<GraphNodeView[]>([])
  const [edges, setEdges] = useState<FlowEdgeView[]>([])
  const [truncated, setTruncated] = useState(false)
  const [loading, setLoading] = useState(false)

  // 影响面
  const [showImpact, setShowImpact] = useState(false)
  const [impact, setImpact] = useState<ImpactResult | null>(null)
  const [impactedIds, setImpactedIds] = useState<Set<string>>(new Set())

  // 选中节点 → 加载下游血缘
  const handleSelect = useCallback(async (node: GraphNodeView) => {
    setSelectedNode(node)
    setShowImpact(false)
    setImpact(null)
    setLoading(true)
    try {
      if (node.type === "COLUMN") {
        const res = await fetchColumnDownstream(node.id, 10)
        if (res.data) {
          setNodes(res.data.nodes ?? [])
          setEdges(res.data.edges ?? [])
          setTruncated(res.data.truncated ?? false)
          setGranularity("COLUMN")
        }
      } else {
        const res = await fetchDownstream(node.id, 10, granularity)
        if (res.data) {
          setNodes(res.data.nodes ?? [])
          setEdges(res.data.edges ?? [])
          setTruncated(res.data.truncated ?? false)
        }
      }
    } catch {
      setNodes([])
      setEdges([])
    } finally {
      setLoading(false)
    }
  }, [granularity])

  // 切换粒度
  const handleToggleGranularity = useCallback(() => {
    const next = granularity === "TABLE" ? "COLUMN" : "TABLE"
    setGranularity(next as Granularity)
    if (selectedNode) {
      handleSelect({ ...selectedNode, granularity: next as Granularity } as GraphNodeView)
    }
  }, [granularity, selectedNode, handleSelect])

  // 影响面分析
  const handleImpact = useCallback(async () => {
    if (!selectedNode) return
    setShowImpact(true)
    setLoading(true)
    try {
      const res = await fetchImpact(selectedNode.id, 20)
      if (res.data) {
        setImpact(res.data)
        const ids = new Set<string>(res.data.downstream?.map((n: GraphNodeView) => n.id) ?? [])
        ids.add(selectedNode.id)
        setImpactedIds(ids)
      }
    } catch {
      setImpact(null)
    } finally {
      setLoading(false)
    }
  }, [selectedNode])

  return (
    <div className="flex h-full gap-0">
      {/* 左侧三级树 */}
      <aside className="w-64 shrink-0 border-r">
        <LineageTree onSelect={handleSelect} />
      </aside>

      {/* 右侧主区 */}
      <main className="flex-1 flex flex-col min-w-0">
        {/* 操作栏 */}
        {selectedNode && (
          <div className="flex items-center gap-2 px-4 py-2 shrink-0">
            <span className="text-sm font-medium truncate">{selectedNode.name}</span>
            {selectedNode.type && (
              <span className="text-xs text-muted-foreground">{selectedNode.type}</span>
            )}
            <button
              className="ml-auto text-xs px-2 py-1 rounded-md bg-muted hover:bg-muted/70
                         text-muted-foreground transition-colors"
              onClick={handleImpact}
            >
              {t("impactTitle")}
            </button>
          </div>
        )}

        {/* 画布区 */}
        <div className="flex-1 min-h-0">
          {loading ? (
            <div className="flex items-center justify-center h-full text-sm text-muted-foreground">
              {t("loading")}
            </div>
          ) : showImpact && impact ? (
            <ImpactPanel
              impact={impact}
              selectedId={selectedNode?.id}
              impactedIds={impactedIds}
              onClose={() => { setShowImpact(false); setImpact(null); setImpactedIds(new Set()); }}
              onSelect={handleSelect}
            />
          ) : nodes.length > 0 || edges.length > 0 ? (
            <LineageFlow
              nodes={nodes}
              edges={edges}
              granularity={granularity}
              selectedId={selectedNode?.id}
              impactedIds={impactedIds}
              truncated={truncated}
              onSelect={handleSelect}
              onToggleGranularity={handleToggleGranularity}
            />
          ) : (
            <div className="flex items-center justify-center h-full text-sm text-muted-foreground">
              {selectedNode ? t("empty") : t("expand")}
            </div>
          )}
        </div>
      </main>
    </div>
  )
}
