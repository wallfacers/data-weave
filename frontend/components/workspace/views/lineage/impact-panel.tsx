"use client"

/**
 * 影响面面板：选中节点的全下游可达集合高亮。
 *
 * 设计约束（DESIGN.md）：语义 token，无分割线，hugeicons，不手写 dark:。
 */
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  ArrowRight02Icon,
  Cancel01Icon,
  Table01Icon,
  ColumnInsertIcon,
} from "@hugeicons/core-free-icons"
import type { GraphNodeView, ImpactResult } from "@/lib/lineage-api"

interface Props {
  impact: ImpactResult
  selectedId?: string
  impactedIds: Set<string>
  onClose: () => void
  onSelect?: (node: GraphNodeView) => void
}

export function ImpactPanel({
  impact,
  selectedId,
  impactedIds,
  onClose,
  onSelect,
}: Props) {
  const t = useTranslations("lineageView")

  return (
    <div className="flex flex-col h-full">
      {/* 标题栏 */}
      <div className="flex items-center gap-2 px-4 py-2 shrink-0">
        <span className="text-sm font-medium">{t("impactTitle")}</span>
        <span className="text-xs text-muted-foreground">
          {t("impactDownstream", { count: impact.nodeCount })}
        </span>
        {impact.truncated && (
          <span className="text-xs text-muted-foreground bg-muted px-1.5 py-0.5 rounded">
            {t("truncated")}
          </span>
        )}
        <button
          className="ml-auto text-muted-foreground hover:text-foreground transition-colors"
          onClick={onClose}
        >
          <HugeiconsIcon icon={Cancel01Icon} className="size-4" />
        </button>
      </div>

      {/* 根节点 */}
      {impact.root && (
        <div className="px-4 py-2 shrink-0">
          <div
            className={`flex items-center gap-2 px-3 py-2 rounded-md text-sm
              ${impact.root.id === selectedId ? "bg-primary/10 ring-1 ring-primary/20" : "bg-muted/50"}`}
          >
            <HugeiconsIcon icon={Table01Icon} className="size-4 text-muted-foreground shrink-0" />
            <span className="truncate font-medium">{impact.root.name}</span>
            {impact.root.layer && (
              <span className="text-xs text-muted-foreground ml-auto shrink-0">
                {impact.root.layer}
              </span>
            )}
          </div>
          <div className="flex justify-center py-1">
            <HugeiconsIcon icon={ArrowRight02Icon} className="size-4 text-muted-foreground" />
          </div>
        </div>
      )}

      {/* 下游节点列表 */}
      <div className="flex-1 overflow-auto px-4 pb-4">
        {impact.downstream.length === 0 ? (
          <div className="text-sm text-muted-foreground py-4 text-center">
            {t("empty")}
          </div>
        ) : (
          <div className="space-y-1">
            {impact.downstream.map((node) => {
              const isHighlighted = impactedIds.has(node.id)
              const Icon = node.type === "COLUMN" ? ColumnInsertIcon : Table01Icon
              return (
                <button
                  key={node.id}
                  className={`flex items-center gap-2 w-full px-3 py-2 rounded-md text-sm text-left
                    transition-colors hover:bg-muted/50
                    ${isHighlighted ? "bg-primary/10 ring-1 ring-primary/20" : ""}`}
                  onClick={() => onSelect?.(node)}
                >
                  <HugeiconsIcon icon={Icon} className="size-4 text-muted-foreground shrink-0" />
                  <span className="truncate">{node.name}</span>
                  <span className="text-[10px] text-muted-foreground shrink-0 ml-auto">
                    {node.type}
                  </span>
                  {node.layer && (
                    <span className="text-[10px] text-muted-foreground shrink-0">{node.layer}</span>
                  )}
                </button>
              )
            })}
          </div>
        )}
      </div>
    </div>
  )
}
