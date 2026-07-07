"use client"

/**
 * 血缘详情面板 —— DetailPanelShell + headerExtra Tabs（节点 / 边 / 影响）。
 *
 * 复用 DetailPanelShell（领域无关壳：title/close/loading/error/hasData/headerExtra）。
 * headerExtra 走下划线式 Tabs，选中项驱动面板 content。
 *
 * 设计约束（DESIGN.md）：语义 token、无分割线、hugeicons、不手写 dark:。
 */
import { useTranslations } from "next-intl"
import { DetailPanelShell } from "@/components/workspace/detail-panel-shell"
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { Badge } from "@/components/ui/badge"
import type { FlowEdgeView, GraphNodeView, ImpactResult } from "@/lib/lineage-api"
import { readNodeAttrs, type LineageNodeAttrs } from "@/lib/lineage-api"
import type { LineagePanelTab } from "@/lib/workspace/lineage-selection-store"
import { useLineageSelection } from "@/lib/workspace/lineage-selection-store"

// ─── Node content ─────────────────────────────────────

function NodeDetailContent({ node }: { node: GraphNodeView }) {
  const t = useTranslations("lineageView")
  const attrs = readNodeAttrs(node)

  return (
    <div className="flex flex-col gap-3">
      {/* 层 */}
      {attrs.layer && (
        <Row label={t("nodeLayer")}>
          <Badge variant="secondary">{attrs.layer}</Badge>
        </Row>
      )}

      {/* 产出任务 */}
      {attrs.producers && attrs.producers.length > 0 && (
        <Row label={t("nodeProducers")}>
          <span className="text-xs text-muted-foreground truncate">
            {attrs.producers.join("、")}
          </span>
        </Row>
      )}

      {/* 新鲜度 */}
      {attrs.lastSyncDate && (
        <Row label={t("nodeLastSync")}>
          <span className="text-xs tabular-nums">{attrs.lastSyncDate}</span>
        </Row>
      )}

      {/* 今日 synced rows */}
      {attrs.syncedRowsToday != null && (
        <Row label={t("nodeSyncedToday")}>
          <span className="text-xs tabular-nums">
            {attrs.syncedRowsToday.toLocaleString()}
          </span>
        </Row>
      )}

      {/* 类型 */}
      <Row label={t("nodeType")}>
        <Badge variant="outline">{node.type}</Badge>
      </Row>

      {/* 粒度 */}
      {node.granularity && (
        <Row label={t("nodeGranularity")}>
          <span className="text-xs">{node.granularity}</span>
        </Row>
      )}

      {/* 未提供时提示 */}
      {!attrs.layer && !attrs.producers?.length && !attrs.lastSyncDate && attrs.syncedRowsToday == null && (
        <span className="text-xs text-muted-foreground">{t("nodeNoRichData")}</span>
      )}
    </div>
  )
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex items-start gap-2">
      <span className="w-20 shrink-0 text-xs text-muted-foreground">{label}</span>
      <span className="min-w-0 flex-1">{children}</span>
    </div>
  )
}

// ─── Edge content (thin, filled in US4) ───────────────

function EdgeDetailContent({ edge, nodes }: { edge: FlowEdgeView; nodes: GraphNodeView[] }) {
  const t = useTranslations("lineageView")
  const fromName = nodes.find((n) => n.id === edge.from)?.name ?? edge.from
  const toName = nodes.find((n) => n.id === edge.to)?.name ?? edge.to

  return (
    <div className="flex flex-col gap-3">
      <div className="rounded-md bg-muted/50 px-3 py-2 text-xs">
        <span className="font-medium">{fromName}</span>
        <span className="mx-1.5 text-muted-foreground">→</span>
        <span className="font-medium">{toName}</span>
      </div>
      {edge.confidence && <Row label={t("edgeConfidence")}><span className="text-xs">{edge.confidence}</span></Row>}
      {edge.source && <Row label={t("edgeSource")}><span className="text-xs">{edge.source}</span></Row>}
    </div>
  )
}

// ─── Impact content (thin, filled in US3) ─────────────

function ImpactContent({ impact }: { impact: ImpactResult }) {
  const t = useTranslations("lineageView")
  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center gap-2">
        <span className="text-sm font-medium">{impact.root.name}</span>
        <Badge variant="secondary">{impact.nodeCount}</Badge>
      </div>
      {impact.truncated && (
        <span className="text-xs text-muted-foreground">{t("truncated")}</span>
      )}
      {impact.edges.length === 0 && (
        <span className="text-xs text-muted-foreground">{t("impactNoEdges")}</span>
      )}
    </div>
  )
}

// ─── Shell ────────────────────────────────────────────

export function LineageDetailPanel() {
  const t = useTranslations("lineageView")
  const sel = useLineageSelection()
  const { selectedNode, selectedEdge, impact, panelTab, panelOpen, closePanel, setPanelTab } = sel

  if (!panelOpen) return null

  const title = selectedNode?.name ?? selectedEdge
    ? `${t("edgeDetailTitle")}`
    : ""

  // Loading/error 从外层传入（view 管理刷新）。面板内加载由 view 传 loading=true 时显示。
  // 这里按 hasData = !!selectedNode || !!selectedEdge 或 impact 非空。

  return (
    <DetailPanelShell
      title={title}
      onClose={closePanel}
      loading={false}
      error={null}
      onRetry={() => {}}
      hasData={!!selectedNode || !!selectedEdge || !!impact}
      headerExtra={
        <Tabs
          value={panelTab}
          onValueChange={(v) => setPanelTab(v as LineagePanelTab)}
        >
          <TabsList size="sm" className="px-4">
            <TabsTrigger value="node">{t("nodeTab")}</TabsTrigger>
            <TabsTrigger value="edge">{t("edgeTab")}</TabsTrigger>
            <TabsTrigger value="impact">{t("impactTab")}</TabsTrigger>
          </TabsList>
        </Tabs>
      }
    >
      {panelTab === "node" && selectedNode && <NodeDetailContent node={selectedNode} />}
      {panelTab === "node" && !selectedNode && (
        <span className="text-xs text-muted-foreground">{t("selectNodeHint")}</span>
      )}

      {panelTab === "edge" && selectedEdge && (
        <EdgeDetailContent
          edge={selectedEdge}
          nodes={[]}
        />
      )}
      {panelTab === "edge" && !selectedEdge && (
        <span className="text-xs text-muted-foreground">{t("selectEdgeHint")}</span>
      )}

      {panelTab === "impact" && impact && <ImpactContent impact={impact} />}
      {panelTab === "impact" && !impact && (
        <span className="text-xs text-muted-foreground">{t("impactPlaceholder")}</span>
      )}
    </DetailPanelShell>
  )
}
