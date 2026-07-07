"use client"

/**
 * 影响面内容组件（供 lineage-detail-panel 的 impact Tab 使用，052 迁入 DetailPanelShell）。
 *
 * 去除自有外壳（header/close），由父级 DetailPanelShell 提供 title+关闭。
 * 新增 reachableTotal 与 nodeCount 区分（FR-013）：真实可达总数 vs 当前页条数。
 *
 * 设计约束（DESIGN.md）：语义 token、无分割线、hugeicons、不手写 dark:。
 */
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  ArrowRight02Icon,
  Table01Icon,
  ColumnInsertIcon,
} from "@hugeicons/core-free-icons"
import type { GraphNodeView, ImpactResult } from "@/lib/lineage-api"

export interface ImpactDetailContentProps {
  impact: ImpactResult
  selectedId?: string
  onSelect?: (node: GraphNodeView) => void
}

export function ImpactDetailContent({ impact, selectedId, onSelect }: ImpactDetailContentProps) {
  const t = useTranslations("lineageView")

  return (
    <div className="flex flex-col gap-3">
      {/* 计数：reachableTotal 优先，nodeCount 兜底 */}
      <div className="flex items-center gap-2">
        <span className="text-xs font-medium text-muted-foreground">
          {impact.totalIsLowerBound && impact.reachableTotal != null
            ? t("impactReachableLowerBound", { count: impact.reachableTotal })
            : t("impactDownstream", { count: impact.reachableTotal ?? impact.nodeCount })}
        </span>
        {impact.truncated && (
          <span className="rounded bg-muted px-1.5 py-0.5 text-[10px] text-muted-foreground">
            {t("truncated")}
          </span>
        )}
      </div>

      {/* 根节点 */}
      {impact.root && (
        <div className="rounded-md bg-muted/50 px-3 py-2">
          <div className="flex items-center gap-2 text-sm">
            <HugeiconsIcon icon={Table01Icon} className="size-4 text-muted-foreground shrink-0" />
            <span className="truncate font-medium">{impact.root.name}</span>
          </div>
          <div className="flex justify-center py-1">
            <HugeiconsIcon icon={ArrowRight02Icon} className="size-4 text-muted-foreground" />
          </div>
        </div>
      )}

      {/* 下游节点列表 */}
      {(impact.downstream?.length ?? 0) === 0 ? (
        <span className="text-xs text-muted-foreground">{t("empty")}</span>
      ) : (
        <div className="space-y-1">
          {impact.downstream.map((node) => {
            const Icon = node.type === "COLUMN" ? ColumnInsertIcon : Table01Icon
            return (
              <button
                key={node.id}
                className="flex w-full items-center gap-2 rounded-md px-3 py-1.5 text-left text-xs transition-colors hover:bg-muted/50"
                onClick={() => onSelect?.(node)}
              >
                <HugeiconsIcon icon={Icon} className="size-3.5 text-muted-foreground shrink-0" />
                <span className="truncate">{node.name}</span>
                <span className="ml-auto shrink-0 text-[10px] text-muted-foreground">{node.type}</span>
              </button>
            )
          })}
        </div>
      )}
    </div>
  )
}
