"use client"

/**
 * 血缘详情面板 —— DetailPanelShell + headerExtra Tabs（节点 / 边 / 影响）。
 *
 * 复用 DetailPanelShell（领域无关壳：title/close/loading/error/hasData/headerExtra）。
 * headerExtra 走下划线式 Tabs，选中项驱动面板 content。
 * 边 Tab 迁入 EdgeCorrectionsContent（确认/剔除/撤销 + 未解析提示，FR-026）；
 * 影响 Tab 迁入 ImpactDetailContent（reachableTotal 与 nodeCount 区分，FR-013）。
 *
 * 设计约束（DESIGN.md）：语义 token、无分割线、hugeicons、不手写 dark:。
 */
import { useTranslations } from "next-intl"
import { DetailPanelShell } from "@/components/workspace/detail-panel-shell"
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { Badge } from "@/components/ui/badge"
import type { GraphNodeView } from "@/lib/lineage-api"
import { readNodeAttrs } from "@/lib/lineage-api"
import type { LineagePanelTab } from "@/lib/workspace/lineage-selection-store"
import { useLineageSelection } from "@/lib/workspace/lineage-selection-store"
import { EdgeCorrectionsContent } from "./edge-detail-panel"
import { ImpactDetailContent } from "./impact-panel"

export interface LineageDetailPanelProps {
  /** 当前图内所有节点（边纠正需要解析名称 + impact 选择导航）。 */
  allNodes: GraphNodeView[]
  /** 边纠正成功后刷新子图（重新查询以反映 REMOVED 状态）。 */
  onEdgeChanged: () => void
  /** impact 列表点击节点 → 导航选中。 */
  onImpactSelectNode?: (node: GraphNodeView) => void
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex items-center gap-2">
      <span className="w-20 shrink-0 text-left text-xs text-muted-foreground">{label}</span>
      <span className="flex min-w-0 flex-1 items-center">{children}</span>
    </div>
  )
}

function NodeDetailContent({ node }: { node: GraphNodeView }) {
  const t = useTranslations("lineageView")
  const attrs = readNodeAttrs(node)

  return (
    <div className="flex flex-col gap-3">
      {attrs.layer && (
        <Row label={t("nodeLayer")}>
          <Badge variant="secondary">{attrs.layer}</Badge>
        </Row>
      )}
      {attrs.producers && attrs.producers.length > 0 && (
        <Row label={t("nodeProducers")}>
          <span className="text-xs text-muted-foreground truncate">{attrs.producers.join("、")}</span>
        </Row>
      )}
      {attrs.lastSyncDate && (
        <Row label={t("nodeLastSync")}>
          <span className="text-xs tabular-nums">{attrs.lastSyncDate}</span>
        </Row>
      )}
      {attrs.syncedRowsToday != null && (
        <Row label={t("nodeSyncedToday")}>
          <span className="text-xs tabular-nums">{attrs.syncedRowsToday.toLocaleString()}</span>
        </Row>
      )}
      <Row label={t("nodeType")}>
        <Badge variant="outline">{node.type}</Badge>
      </Row>
      {node.granularity && (
        <Row label={t("nodeGranularity")}>
          <span className="text-xs">{node.granularity}</span>
        </Row>
      )}
      {!attrs.layer && !attrs.producers?.length && !attrs.lastSyncDate && attrs.syncedRowsToday == null && (
        <span className="text-xs text-muted-foreground">{t("nodeNoRichData")}</span>
      )}
    </div>
  )
}

export function LineageDetailPanel({ allNodes, onEdgeChanged, onImpactSelectNode }: LineageDetailPanelProps) {
  const t = useTranslations("lineageView")
  const sel = useLineageSelection()
  const { selectedNode, selectedEdge, impact, panelTab, panelOpen, closePanel, setPanelTab } = sel

  if (!panelOpen) return null

  const title =
    panelTab === "node" && selectedNode
      ? selectedNode.name
      : panelTab === "edge" && selectedEdge
        ? t("edgeDetailTitle")
        : panelTab === "impact" && impact
          ? impact.root.name
          : ""

  return (
    <DetailPanelShell
      title={title}
      onClose={closePanel}
      loading={false}
      error={null}
      onRetry={() => {}}
      hasData={!!selectedNode || !!selectedEdge || !!impact}
      headerExtra={
        <Tabs value={panelTab} onValueChange={(v) => setPanelTab(v as LineagePanelTab)}>
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
        <EdgeCorrectionsContent edge={selectedEdge} nodes={allNodes} onChanged={onEdgeChanged} />
      )}
      {panelTab === "edge" && !selectedEdge && (
        <span className="text-xs text-muted-foreground">{t("selectEdgeHint")}</span>
      )}

      {panelTab === "impact" && impact && (
        <ImpactDetailContent
          impact={impact}
          selectedId={selectedNode?.id}
          onSelect={onImpactSelectNode}
        />
      )}
      {panelTab === "impact" && !impact && (
        <span className="text-xs text-muted-foreground">{t("impactPlaceholder")}</span>
      )}
    </DetailPanelShell>
  )
}
