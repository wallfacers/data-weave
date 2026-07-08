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
import { useCallback, useEffect, useLayoutEffect, useMemo, useReducer, useState, type MouseEvent as ReactMouseEvent, type PointerEvent as ReactPointerEvent } from "react"
import { useTranslations } from "next-intl"
import { type Node } from "@xyflow/react"
import { motion, useMotionValue, useTransform } from "motion/react"

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
import { LineageFacets } from "@/components/workspace/views/lineage/lineage-facets"
import { LineageToolbar } from "@/components/workspace/views/lineage/lineage-toolbar"
import { LineageDetailPanel } from "@/components/workspace/views/lineage/lineage-detail-panel"
import { LineageLegend } from "@/components/workspace/views/lineage/lineage-legend"
import { LineageSearchHero, LineageSearchCandidates, SearchCandidatesOverlay } from "@/components/workspace/views/lineage/lineage-search-hero"
import { lineageNodeTypes } from "@/components/workspace/nodes/lineage-node"
import { LineageNodeActionsContext, type LineageNodeActions } from "@/components/workspace/nodes/lineage-node-actions-context"
import { lineageToFlow, type LineageLayoutOptions } from "@/lib/workspace/lineage-layout"
import { lineageGraphReducer, initialGraphState } from "@/lib/workspace/lineage-graph"
import { recordRecentAsset } from "@/lib/workspace/lineage-recent"
import { useLineageSelection } from "@/lib/workspace/lineage-selection-store"
import { useMinSpin } from "@/hooks/use-min-spin"

const DEFAULT_DEPTH = 3
const EXPAND_DEPTH = 1
// 054：点数据源/分层加载「一部分子图」——组内表封顶张数 + 各表并入的邻域跳数
const GROUP_TABLE_CAP = 12
const GROUP_DEPTH = 1
// 左栏类目树面板宽度（右缘分割线拖拽，localStorage 持久化）——与数据开发 workflow-canvas-view 1:1
const CATALOG_DEFAULT_WIDTH = 256 // = w-64
// 最小宽度设 224：容纳分面 Segmented（数据源/分层/最近）+ 刷新按钮不换行变形
const CATALOG_MIN_WIDTH = 224
const CATALOG_MAX_WIDTH = 480
const CATALOG_WIDTH_KEY = "dw.lineage.catalogWidth"

export function LineageView({ params }: { params?: Record<string, unknown> }) {
  const t = useTranslations("lineageView")

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
  // 054：当前「组视图」上下文（点数据源/分层加载子图时置位；点单表/列锚定时清空）——供刷新重放
  const [groupCtx, setGroupCtx] = useState<{ tables: GraphNodeView[]; label: string } | null>(null)

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

  // ── 左栏类目树面板宽度（右缘分割线拖拽，localStorage 持久化）——与数据开发 1:1 ──
  // 与 AgentRail 同模式：motion value 驱动渲染，拖拽过程零 React 提交
  const [, setCatalogWidth] = useState(CATALOG_DEFAULT_WIDTH)
  const catalogWidthMotion = useMotionValue(CATALOG_DEFAULT_WIDTH)
  const [catalogHydrated, setCatalogHydrated] = useState(false)
  useLayoutEffect(() => {
    const saved = Number(localStorage.getItem(CATALOG_WIDTH_KEY))
    if (saved >= CATALOG_MIN_WIDTH && saved <= CATALOG_MAX_WIDTH) {
      setCatalogWidth(saved)
      catalogWidthMotion.set(saved)
    }
    setCatalogHydrated(true)
  }, [catalogWidthMotion])
  const catalogWidthStyle = useTransform(catalogWidthMotion, (v) => `${Math.round(v)}px`)
  const catalogWidthProp = catalogHydrated ? catalogWidthStyle : "var(--dw-catalog-width, 256px)"
  const onCatalogResizeDown = useCallback(
    (e: ReactPointerEvent) => {
      e.preventDefault()
      const startX = e.clientX
      const startW = catalogWidthMotion.get()
      let current = startW
      const onMove = (ev: PointerEvent) => {
        current = Math.min(CATALOG_MAX_WIDTH, Math.max(CATALOG_MIN_WIDTH, startW + (ev.clientX - startX)))
        catalogWidthMotion.set(current)
      }
      const onUp = () => {
        window.removeEventListener("pointermove", onMove)
        window.removeEventListener("pointerup", onUp)
        document.body.style.cursor = ""
        document.body.style.userSelect = ""
        setCatalogWidth(current)
        localStorage.setItem(CATALOG_WIDTH_KEY, String(current))
      }
      document.body.style.cursor = "col-resize"
      document.body.style.userSelect = "none"
      window.addEventListener("pointermove", onMove)
      window.addEventListener("pointerup", onUp)
    },
    [catalogWidthMotion],
  )

  // ── 054 US4：按数据源分组泳道开关（FR-010，默认关=徽标视图）──
  const [groupByDatasource, setGroupByDatasource] = useState(false)

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

  // 注：不再持续把 ?open=lineage&anchor= 写回地址栏——workspace 深链逃生舱（workspace.tsx）
  // 消费一次 ?open= 后即 replace("/")，二者互写只产生 churn。分享走工具栏「复制链接」
  // （copyDeepLink 从当前状态重建 URL）；深链恢复走挂载时的 params.anchor（下方 effect）。

  // ── Data fetching ──

  const loadAnchor = useCallback(
    async (nodeId: string, d: LineageDirection = direction, dep: number = depth, gran = granularity) => {
      setLoading(true)
      setError(null)
      setGroupCtx(null) // 单表/列锚定 → 退出组视图
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
        if (res.code !== 0) {
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
          // 054 US3：记入「最近」分面（会话本地；取图内锚点节点的真实名/数据源）
          const anchorNode = (data.nodes ?? []).find((n) => n.id === nodeId)
          if (anchorNode) {
            recordRecentAsset({
              id: anchorNode.id,
              name: anchorNode.name,
              type: anchorNode.type,
              datasourceName:
                typeof anchorNode.attrs?.datasourceName === "string"
                  ? (anchorNode.attrs.datasourceName as string)
                  : undefined,
            })
          }
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

  // ── 054：点数据源/分层 → 加载该组的「一部分子图」──
  // 后端无「按数据源/层取子图」端点，故取组内表（封顶 GROUP_TABLE_CAP）各自 1 跳邻域并集：
  // 先把组内表本身播种为节点（纯孤立表也可见），再并入邻域节点/边去重，一次性 load。
  const loadGroup = useCallback(
    async (tables: GraphNodeView[], label: string) => {
      const picked = tables.slice(0, GROUP_TABLE_CAP)
      if (picked.length === 0) return
      setLoading(true)
      setError(null)
      setGroupCtx({ tables, label })
      dispatch({ type: "reset" })
      try {
        const results = await Promise.all(
          picked.map((tb) => fetchNeighborhood(tb.id, GROUP_DEPTH, "TABLE").catch(() => null)),
        )
        const nodeMap = new Map<string, GraphNodeView>()
        for (const tb of picked) nodeMap.set(tb.id, tb)
        const edgeSeen = new Set<string>()
        const edges: FlowEdgeView[] = []
        for (const res of results) {
          if (!res || res.code !== 0 || !res.data) continue
          for (const n of res.data.nodes ?? []) if (!nodeMap.has(n.id)) nodeMap.set(n.id, n)
          for (const e of res.data.edges ?? []) {
            const k = `${e.from}→${e.to}:${e.granularity}`
            if (!edgeSeen.has(k)) {
              edgeSeen.add(k)
              edges.push(e)
            }
          }
        }
        const capped = tables.length > picked.length
        dispatch({
          type: "load",
          anchorId: picked[0].id,
          nodes: [...nodeMap.values()],
          edges,
          truncated: capped,
        })
        setTruncated(capped)
      } catch {
        setError(t("unavailable"))
      } finally {
        setLoading(false)
        setLastRefreshMs(Date.now())
        setStale(false)
      }
    },
    [t],
  )

  // ── 054 US1：搜索提交（hero 与工具栏共用，提交/回车触发 research D4）──
  const runSearch = useCallback(async () => {
    const q = searchQuery.trim()
    if (!q) return
    setSearching(true)
    setSearchCandidates([])
    try {
      const res = await fetchSearch(q)
      if (res.data) setSearchCandidates(res.data)
    } catch {
      setSearchCandidates([])
    } finally {
      setSearching(false)
    }
  }, [searchQuery])

  const onCandidateSelect = useCallback(
    (c: SearchCandidate) => {
      setSearchCandidates([])
      setSearchQuery("")
      loadAnchor(c.id)
    },
    [loadAnchor],
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
        if (res.code !== 0) {
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
        // 054：保留列级 DERIVES_FROM 边（不再丢弃）→ 列→列连线（FR-012/013）
        const columnEdges = (data?.edges ?? []).filter((e) => e.granularity === "COLUMN")
        dispatch({ type: "expandColumns", tableId, columns, columnEdges })
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
        if (res.code !== 0) {
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
      columnEdgesByTable: graph.columnEdgesByTable,
      impactedNodeIds: allImpactedIds.size > 0 ? allImpactedIds : undefined,
      selectedNodeId: sel.selectedNode?.id ?? null,
      highlightEdgeKeys: allHighlightEdges.size > 0 ? allHighlightEdges : undefined,
      dimUnrelated: pathMode || !!(sel.selectedNode) || pathResult != null,
      groupByDatasource,
    }
  }, [graph.anchorId, graph.columnsByTable, graph.columnEdgesByTable, impact, pathResult, pathMode, sel.selectedNode?.id, groupByDatasource])

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
    if (groupCtx) loadGroup(groupCtx.tables, groupCtx.label)
    else if (graph.anchorId) loadAnchor(graph.anchorId)
  }, [groupCtx, loadGroup, graph.anchorId, loadAnchor])
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
      <div className="flex h-full gap-3 p-3">
        {/* 左侧 catalog 树（054：可折叠——搜索为主入口，数据源树降级为可选分面；FR-001）
            数据开发同款 card + 分割线；右侧 pr-1.5 预留给滚动条+分割线，不可拖拽缩放 */}
        {/* 左侧常驻分面树 —— 数据开发同款 card + 右缘可拖拽分割线（1:1） */}
        <div className="relative flex shrink-0 flex-col pr-1.5">
          <motion.div
            className="flex h-full flex-col overflow-hidden rounded-[var(--radius-lg)] border bg-card shadow-lg"
            style={{ width: catalogWidthProp }}
          >
            <LineageFacets onSelect={handleTreeSelect} onSelectGroup={loadGroup} />
          </motion.div>
          {/* 右缘分割线拖拽 */}
          <div
            onPointerDown={onCatalogResizeDown}
            role="separator"
            aria-orientation="vertical"
            aria-label={t("resizeCatalogPanel")}
            className="group/resize absolute inset-y-3 right-0 z-20 flex w-2 cursor-col-resize touch-none items-center justify-center"
          >
            <div className="h-12 w-0.5 rounded-full bg-border/0 transition-colors group-hover/resize:bg-border" />
          </div>
        </div>

        {/* 中间主区 */}
        <main className="flex min-w-0 flex-1 flex-col overflow-hidden rounded-[var(--radius-lg)] border bg-card shadow-lg">
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
            groupByDatasource={groupByDatasource}
            onGroupByDatasourceChange={setGroupByDatasource}
            searchQuery={searchQuery}
            onSearchChange={setSearchQuery}
            onSearchSubmit={runSearch}
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

          {/* 搜索候选下拉（叠加在画布上方；054 显示数据源标注区分同名跨库，仅已锚定时——未锚定走 hero） */}
          {searchCandidates.length > 0 && graph.anchorId && (
            <SearchCandidatesOverlay
              candidates={searchCandidates}
              onSelect={onCandidateSelect}
              onDismiss={() => setSearchCandidates([])}
            />
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
            nodesDraggable
          >
            {/* 054 US1：未锚定时搜索 hero 为视觉主入口（居中、默认聚焦） */}
            {!graph.anchorId && !loading && !error && (
              <LineageSearchHero
                query={searchQuery}
                searching={searching}
                candidates={searchCandidates}
                onQueryChange={setSearchQuery}
                onSubmit={runSearch}
                onSelect={onCandidateSelect}
                onDismiss={() => setSearchCandidates([])}
              />
            )}
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
