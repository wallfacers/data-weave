"use client"

/**
 * 血缘图探索器 —— 三栏视图（左 catalog 树 · 中画布+嵌入面板 · 无右侧固定列）。
 *
 * 052 重写：弃用 lineage-flow.tsx（手绘 SVG 网格），改为复用工作流 DAG 栈：
 *   DagRenderer（ReactFlow 画布）+ FlowCanvasWithPanel（可调宽嵌入面板壳）。
 * 分层布局走 dagre LR（lineage-layout），数据累积走 lineage-graph reducer。
 *
 * 设计约束（DESIGN.md）：语义 token、三栏无分割线、gap-* / size-*、hugeicons、不手写 dark:。
 */
import { useCallback, useEffect, useMemo, useReducer, useState, type MouseEvent as ReactMouseEvent } from "react"
import { useSearchParams, useRouter } from "next/navigation"
import { useTranslations } from "next-intl"
import { type Node } from "@xyflow/react"

import type {
  GraphNodeView,
  FlowEdgeView,
  LineageDirection,
  ImpactResult,
} from "@/lib/lineage-api"
import {
  fetchNeighborhood,
  fetchTableColumnLineage,
  fetchUpstream,
  fetchDownstream,
  fetchColumnUpstream,
  fetchColumnDownstream,
  fetchImpact,
  fetchPaths,
  fetchSearch,
  type LineagePath,
  type SearchCandidate,
} from "@/lib/lineage-api"
import { FlowCanvasWithPanel } from "@/components/workspace/flow-canvas-with-panel"
import { LineageTree } from "@/components/workspace/views/lineage/lineage-tree"
import { LineageToolbar } from "@/components/workspace/views/lineage/lineage-toolbar"
import { LineageDetailPanel } from "@/components/workspace/views/lineage/lineage-detail-panel"
import { LineageLegend } from "@/components/workspace/views/lineage/lineage-legend"
import { lineageNodeTypes } from "@/components/workspace/nodes/lineage-node"
import { LineageNodeActionsContext, type LineageNodeActions } from "@/components/workspace/nodes/lineage-node-actions-context"
import { lineageToFlow, type LineageLayoutOptions } from "@/lib/workspace/lineage-layout"
import { lineageGraphReducer, initialGraphState } from "@/lib/workspace/lineage-graph"
import { useLineageSelection } from "@/lib/workspace/lineage-selection-store"
import { useMinSpin } from "@/hooks/use-min-spin"

const DEFAULT_DEPTH = 3
const EXPAND_DEPTH = 1

export function LineageView({ params }: { params?: Record<string, unknown> }) {
  const t = useTranslations("lineageView")
  const router = useRouter()
  const searchParams = useSearchParams()

  // ── ViewState ──
  const [direction, setDirection] = useState<LineageDirection>(
    (params?.dir as LineageDirection) ?? "both",
  )
  const [depth, setDepth] = useState(Number(params?.depth) || DEFAULT_DEPTH)
  const [granularity, setGranularity] = useState<"TABLE" | "COLUMN">(
    (params?.gran as "TABLE" | "COLUMN") ?? "TABLE",
  )

  // ── Graph state ──
  const [graph, dispatch] = useReducer(lineageGraphReducer, initialGraphState())

  // ── Loading / error ──
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [lastRefreshMs, setLastRefreshMs] = useState<number | null>(null)
  const [stale, setStale] = useState(false)
  const [truncated, setTruncated] = useState(false)

  // ── Selection store ──
  const sel = useLineageSelection()

  // ── Impact ──
  const [impact, setImpact] = useState<ImpactResult | null>(null)
  const [impactLoading, setImpactLoading] = useState(false)

  // ── Path（US3）──
  const [pathMode, setPathMode] = useState(false)
  const [pathFrom, setPathFrom] = useState<string | null>(null)
  const [pathTo, setPathTo] = useState<string | null>(null)
  const [pathResult, setPathResult] = useState<LineagePath | null>(null)
  const [pathLoading, setPathLoading] = useState(false)

  // ── Search（US2）──
  const [searchQuery, setSearchQuery] = useState("")
  const [searching, setSearching] = useState(false)
  const [searchCandidates, setSearchCandidates] = useState<SearchCandidate[]>([])

  // ── Escape 关面板 ──
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape" && sel.panelOpen) {
        sel.closePanel()
      }
    }
    window.addEventListener("keydown", onKey)
    return () => window.removeEventListener("keydown", onKey)
  }, [sel])

  // ── Deep link sync（US5 base）──
  useEffect(() => {
    if (!graph.anchorId) return
    const q = new URLSearchParams(searchParams.toString())
    q.set("open", "lineage")
    q.set("anchor", graph.anchorId)
    q.set("dir", direction)
    q.set("depth", String(depth))
    q.set("gran", granularity)
    const href = `/?${q.toString()}`
    router.replace(href, { scroll: false })
  }, [graph.anchorId, direction, depth, granularity]) // eslint-disable-line react-hooks/exhaustive-deps

  // ── Data fetching ──

  const loadAnchor = useCallback(
    async (nodeId: string, d: LineageDirection = direction, dep: number = depth, gran = granularity) => {
      setLoading(true)
      setError(null)
      dispatch({ type: "reset" })
      try {
        let res
        const filters = undefined // US4+ 加 filters
        if (gran === "COLUMN" || (gran === "TABLE" && d !== "both")) {
          const col = gran === "COLUMN"
          if (d === "upstream") {
            res = col
              ? await fetchColumnUpstream(nodeId, dep)
              : await fetchUpstream(nodeId, dep, gran, filters)
          } else {
            res = col
              ? await fetchColumnDownstream(nodeId, dep)
              : await fetchDownstream(nodeId, dep, gran, filters)
          }
        } else {
          res = await fetchNeighborhood(nodeId, dep, gran, filters)
        }
        if (res.code === "lineage.store_unavailable") {
          setError(t("unavailable"))
          return
        }
        const data = res.data
        if (data) {
          dispatch({
            type: "load",
            anchorId: nodeId,
            nodes: data.nodes ?? [],
            edges: data.edges ?? [],
            truncated: data.truncated ?? false,
          })
          setTruncated(data.truncated ?? false)
        }
      } catch {
        setError(t("unavailable"))
      } finally {
        setLoading(false)
        setLastRefreshMs(Date.now())
        setStale(false)
      }
    },
    [direction, depth, granularity, t],
  )

  // ── 深链恢复：挂载时若 params 带 anchor，自动加载该锚点（US5/FR-021）──
  useEffect(() => {
    const a = params?.anchor
    if (typeof a === "string" && a && !graph.anchorId) {
      loadAnchor(a)
    }
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  // ── 列展开（chevron，FR-015）：表节点 → 内联列清单（参与列级血缘的列高亮）──
  const toggleColumns = useCallback(
    async (tableId: string) => {
      if (graph.columnsByTable[tableId]) {
        dispatch({ type: "collapseColumns", tableId })
        return
      }
      try {
        const res = await fetchTableColumnLineage(tableId)
        if (res.code === "lineage.store_unavailable") {
          setError(t("unavailable"))
          return
        }
        const data = res.data
        // 本表列（parentId==tableId）；hasLineage = 该列出现在任一列级派生边
        const edgeCols = new Set<string>()
        for (const e of data?.edges ?? []) {
          edgeCols.add(e.from)
          edgeCols.add(e.to)
        }
        const columns = (data?.nodes ?? [])
          .filter((n) => n.parentId === tableId)
          .map((n) => ({
            id: n.id,
            name: n.name,
            dataType: typeof n.attrs?.dataType === "string" ? (n.attrs.dataType as string) : undefined,
            ordinal: typeof n.attrs?.ordinal === "number" ? (n.attrs.ordinal as number) : undefined,
            hasLineage: edgeCols.has(n.id),
          }))
          .sort((a, b) => (a.ordinal ?? 0) - (b.ordinal ?? 0))
        dispatch({ type: "expandColumns", tableId, columns })
      } catch {
        // 静默：列展开失败不阻断主图
      }
    },
    [graph.columnsByTable, t],
  )

  // ── 邻居增量展开（双击，FR-005）：原地追加相邻一跳、保留视图 ──
  const toggleNeighbors = useCallback(
    async (nodeId: string) => {
      if (graph.expanded.has(nodeId)) {
        dispatch({ type: "collapse", nodeId })
        return
      }
      const gran = granularity === "TABLE" ? "TABLE" : "COLUMN"
      try {
        const res = await fetchNeighborhood(nodeId, EXPAND_DEPTH, gran as "TABLE" | "COLUMN")
        if (res.code === "lineage.store_unavailable") {
          setError(t("unavailable"))
          return
        }
        const data = res.data
        dispatch({
          type: "expand",
          nodeId,
          nodes: data?.nodes ?? [],
          edges: data?.edges ?? [],
        })
      } catch {
        // silent
      }
    },
    [graph.expanded, granularity, t],
  )

  // ── Impact analysis ──
  const runImpact = useCallback(async () => {
    const anchor = graph.anchorId
    if (!anchor) return
    setImpactLoading(true)
    setImpact(null)
    try {
      const res = await fetchImpact(anchor, 20)
      if (res.data) {
        setImpact(res.data)
        sel.setImpact(res.data)
        sel.showImpact()
      }
    } catch {
      // silent
    } finally {
      setImpactLoading(false)
    }
  }, [graph.anchorId, sel])

  // ── Handle node select from tree ──
  const handleTreeSelect = useCallback(
    (node: GraphNodeView) => {
      sel.closePanel()
      setGranularity(node.type === "COLUMN" ? "COLUMN" : "TABLE")
      loadAnchor(node.id)
    },
    [loadAnchor, sel],
  )

  // ── Handle node click on canvas ──
  const handleCanvasNodeClick = useCallback(
    async (_event: ReactMouseEvent, node: Node) => {
      if (pathMode) {
        if (!pathFrom) {
          setPathFrom(node.id)
        } else if (node.id !== pathFrom) {
          const to = node.id
          setPathTo(to)
          setPathLoading(true)
          setPathMode(false)
          try {
            const res = await fetchPaths(pathFrom, to, depth)
            setPathResult(res.data ?? null)
          } catch {
            setPathResult(null)
          } finally {
            setPathLoading(false)
            setPathFrom(null)
            setPathTo(null)
          }
        }
        return
      }
      // Normal mode: select node
      const gNode = graph.nodes.find((n) => n.id === node.id)
      if (gNode) sel.selectNode(gNode)
    },
    [pathMode, pathFrom, depth, graph.nodes, sel],
  )

  // ── Handle edge click on canvas → selectEdge ──
  const handleCanvasEdgeClick = useCallback(
    (_event: ReactMouseEvent, edge: import("@xyflow/react").Edge) => {
      const flowEdge = (edge.data as FlowEdgeView | undefined) ?? graph.edges.find(
        (e) => e.from === edge.source && e.to === edge.target,
      )
      if (flowEdge) sel.selectEdge(flowEdge)
    },
    [graph.edges, sel],
  )

  // ── Handle pane click → deselect ──
  const handlePaneClick = useCallback(() => {
    sel.closePanel()
  }, [sel])

  // ── Node actions context ──
  const nodeActions: LineageNodeActions = useMemo(
    () => ({
      onSelectNode: (nodeId) => {
        const gNode = graph.nodes.find((n) => n.id === nodeId)
        if (gNode) sel.selectNode(gNode)
      },
      onToggleExpand: toggleColumns,
    }),
    [graph.nodes, sel, toggleColumns],
  )

  // ── 双击节点 → 邻居增量展开（FR-005）──
  const handleCanvasNodeDoubleClick = useCallback(
    (_event: ReactMouseEvent, node: Node) => {
      void toggleNeighbors(node.id)
    },
    [toggleNeighbors],
  )

  // ── Layout ──
  // ── Path mode actions ──
  const enterPathMode = useCallback(() => {
    setPathResult(null)
    setPathFrom(null)
    setPathTo(null)
    setPathMode(true)
  }, [])
  const cancelPathMode = useCallback(() => {
    setPathMode(false)
    setPathFrom(null)
    setPathTo(null)
  }, [])

  // ── Layout ──
  const layoutOpts: LineageLayoutOptions = useMemo(() => {
    const impactedIds = new Set(impact?.downstream?.map((n) => n.id) ?? [])
    if (impact?.root) impactedIds.add(impact.root.id)
    const impactEdgeKeys = new Set(
      (impact?.edges ?? []).map((e) => `${e.from}→${e.to}`),
    )
    const pathIds = new Set(pathResult?.nodes?.map((n) => n.id) ?? [])
    const pathEdgeKeys = new Set(
      (pathResult?.edges ?? []).map((e) => `${e.from}→${e.to}`),
    )
    // 合并影响 + 路径高亮
    const allHighlightEdges = new Set([...impactEdgeKeys, ...pathEdgeKeys])
    const allImpactedIds = new Set([...impactedIds, ...pathIds])
    return {
      anchorId: graph.anchorId ?? undefined,
      // chevron 指示列展开态（内联列清单）
      expandedNodeIds: new Set(Object.keys(graph.columnsByTable)),
      columnsByTable: graph.columnsByTable,
      impactedNodeIds: allImpactedIds.size > 0 ? allImpactedIds : undefined,
      selectedNodeId: sel.selectedNode?.id ?? null,
      highlightEdgeKeys: allHighlightEdges.size > 0 ? allHighlightEdges : undefined,
      dimUnrelated: pathMode || !!(sel.selectedNode) || pathResult != null,
    }
  }, [graph.anchorId, graph.columnsByTable, impact, pathResult, pathMode, sel.selectedNode?.id])

  const layout = useMemo(
    () =>
      lineageToFlow(
        { nodes: graph.nodes, edges: graph.edges },
        layoutOpts,
      ),
    [graph.nodes, graph.edges, layoutOpts],
  )

  // ── Refresh ──
  const handleRefresh = useCallback(() => {
    if (graph.anchorId) loadAnchor(graph.anchorId)
  }, [graph.anchorId, loadAnchor])
  const refreshing = useMinSpin(loading)

  // ── Deep link copy ──
  const copyDeepLink = useCallback(() => {
    const q = new URLSearchParams()
    q.set("open", "lineage")
    if (graph.anchorId) q.set("anchor", graph.anchorId)
    q.set("dir", direction)
    q.set("depth", String(depth))
    q.set("gran", granularity)
    navigator.clipboard.writeText(`${window.location.origin}/?${q.toString()}`).catch(() => {})
  }, [graph.anchorId, direction, depth, granularity])

  // ── Export subgraph JSON ──
  const exportGraph = useCallback(() => {
    const blob = new Blob(
      [JSON.stringify({ nodes: graph.nodes, edges: graph.edges, anchorId: graph.anchorId }, null, 2)],
      { type: "application/json" },
    )
    const url = URL.createObjectURL(blob)
    const a = document.createElement("a")
    a.href = url
    a.download = `lineage-${graph.anchorId ?? "export"}.json`
    a.click()
    URL.revokeObjectURL(url)
  }, [graph.nodes, graph.edges, graph.anchorId])

  // ── onPaneClick → close panel ──
  const onPaneClickForCanvas = useCallback(() => {
    sel.closePanel()
  }, [sel])

  const hasData = graph.nodes.length > 0 && !loading

  return (
    <LineageNodeActionsContext.Provider value={nodeActions}>
      <div className="flex h-full gap-0">
        {/* 左侧 catalog 树 */}
        <aside className="w-64 shrink-0">
          <LineageTree onSelect={handleTreeSelect} />
        </aside>

        {/* 中间主区 */}
        <main className="flex min-w-0 flex-1 flex-col">
          {/* 工具栏 */}
          <LineageToolbar
            direction={direction}
            onDirectionChange={(v) => {
              setDirection(v)
              if (graph.anchorId) loadAnchor(graph.anchorId, v)
            }}
            depth={depth}
            onDepthChange={(v) => {
              setDepth(v)
              if (graph.anchorId) loadAnchor(graph.anchorId, direction, v)
            }}
            granularity={granularity}
            onGranularityChange={(v) => {
              setGranularity(v)
              if (graph.anchorId) loadAnchor(graph.anchorId, direction, depth, v)
            }}
            searchQuery={searchQuery}
            onSearchChange={setSearchQuery}
            onSearchSubmit={
              searchQuery.trim()
                ? async () => {
                    setSearching(true)
                    setSearchCandidates([])
                    try {
                      const res = await fetchSearch(searchQuery.trim())
                      if (res.data) setSearchCandidates(res.data)
                    } catch {
                      setSearchCandidates([])
                    } finally {
                      setSearching(false)
                    }
                  }
                : undefined
            }
            onImpactAnalysis={runImpact}
            onPathHighlight={enterPathMode}
            onCopyDeepLink={copyDeepLink}
            onExport={exportGraph}
            lastRefreshMs={lastRefreshMs}
            refreshing={refreshing}
            stale={stale}
            onRefresh={handleRefresh}
            hasAnchor={!!graph.anchorId}
            loading={loading}
          />

          {/* 路径模式指示 */}
          {pathMode && (
            <div className="flex h-8 shrink-0 items-center gap-2 bg-accent px-3 text-xs">
              <span className="text-muted-foreground">
                {!pathFrom
                  ? t("pathSelectFrom")
                  : t("pathSelectTo")}
              </span>
              <button
                type="button"
                className="ml-auto rounded px-2 py-0.5 text-muted-foreground transition-colors hover:text-foreground"
                onClick={cancelPathMode}
              >
                {t("close")}
              </button>
            </div>
          )}

          {/* 路径结果提示 */}
          {pathResult && !pathMode && (
            <div className="flex h-8 shrink-0 items-center gap-2 bg-accent px-3 text-xs">
              {pathResult.pathExists ? (
                <>
                  <span className="text-muted-foreground">
                    {t("pathExists", {
                      nodes: pathResult.nodes.length,
                      edges: pathResult.edges.length,
                    })}
                  </span>
                  {pathResult.truncated && (
                    <span className="rounded bg-muted px-1 py-0.5 text-[10px] text-muted-foreground">
                      {t("truncated")}
                    </span>
                  )}
                </>
              ) : (
                <span className="text-muted-foreground">{t("pathNotFound")}</span>
              )}
              <button
                type="button"
                className="ml-auto rounded px-2 py-0.5 text-muted-foreground transition-colors hover:text-foreground"
                onClick={() => setPathResult(null)}
              >
                {t("close")}
              </button>
            </div>
          )}

          {/* 搜索候选下拉（叠加在画布上方） */}
          {searchCandidates.length > 0 && (
            <div className="relative z-30 mx-3 -mb-1">
              <div className="absolute top-0 left-0 right-0 max-h-48 overflow-auto rounded-md border bg-popover shadow-lg">
                <div className="flex flex-col p-1">
                  {searchCandidates.map((c) => (
                    <button
                      key={c.id}
                      type="button"
                      className="flex items-center gap-2 rounded px-2 py-1.5 text-left text-xs transition-colors hover:bg-muted"
                      onClick={() => {
                        setSearchCandidates([])
                        setSearchQuery("")
                        loadAnchor(c.id)
                      }}
                    >
                      <span className="truncate font-medium">{c.name}</span>
                      {c.layer && (
                        <span className="shrink-0 rounded bg-muted px-1 py-0.5 text-[10px] text-muted-foreground">
                          {c.layer}
                        </span>
                      )}
                      <span className="ml-auto shrink-0 text-[10px] text-muted-foreground">{c.type}</span>
                    </button>
                  ))}
                </div>
              </div>
            </div>
          )}

          {/* 画布 + 嵌入面板 */}
          <FlowCanvasWithPanel
            rfId="lineage-canvas"
            nodes={layout.nodes}
            edges={layout.edges}
            nodeTypes={lineageNodeTypes}
            loading={loading}
            error={error}
            onRetry={handleRefresh}
            hasData={hasData}
            onNodeClick={handleCanvasNodeClick}
            onNodeDoubleClick={handleCanvasNodeDoubleClick}
            onEdgeClick={handleCanvasEdgeClick}
            onPaneClick={onPaneClickForCanvas}
            panelOpen={sel.panelOpen}
            renderPanel={() => (
              <LineageDetailPanel
                allNodes={graph.nodes}
                onEdgeChanged={() => {
                  if (graph.anchorId) loadAnchor(graph.anchorId)
                }}
                onImpactSelectNode={(node) => {
                  sel.closePanel()
                  loadAnchor(node.id)
                }}
              />
            )}
            panelStorageKey="dw.lineage.panel-width"
            loadingText={t("loading")}
            emptyText={t("emptyCanvasHint")}
            retryText={t("retry")}
            showMiniMap
          >
            {/* 搜索结果无匹配提示（画布层） */}
            {searching && (
              <div className="absolute left-4 top-4 z-10 rounded-md border bg-card px-3 py-2 text-xs text-muted-foreground shadow-sm">
                {t("searching")}
              </div>
            )}
            {/* 边样式图例 */}
            {hasData && <LineageLegend />}
          </FlowCanvasWithPanel>
        </main>
      </div>
    </LineageNodeActionsContext.Provider>
  )
}
