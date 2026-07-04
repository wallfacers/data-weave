"use client"

import { useCallback } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  Database01Icon,
  Edit02Icon,
  Delete02Icon,
  RefreshIcon,
  Notification01Icon,
} from "@hugeicons/core-free-icons"
import { motion, AnimatePresence } from "motion/react"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { cn } from "@/lib/utils"
import type { AssetSummary, AssetDetail, LineageEntryView, QualityBadgeView } from "@/lib/catalog-api"

/** 敏感度 → Badge variant */
function sensitivityVariant(s: string): "secondary" | "default" | "warning" | "destructive" {
  switch (s) {
    case "PUBLIC": return "secondary"
    case "INTERNAL": return "default"
    case "CONFIDENTIAL": return "warning"
    case "PII": return "destructive"
    default: return "secondary"
  }
}

/** 状态 → Badge variant（note: 无 muted variant, RETIRED 用 outline） */
function statusVariant(s: string): "success" | "warning" | "outline" {
  switch (s) {
    case "ACTIVE": return "success"
    case "STALE": return "warning"
    case "RETIRED": return "outline"
    default: return "outline"
  }
}

export interface AssetCardProps {
  asset: AssetSummary
  /** 是否为当前展开的卡片 */
  expanded: boolean
  onToggle: (id: number) => void
  /** 展开态数据（懒加载完成前可为 null） */
  detail?: AssetDetail | null
  lineage?: LineageEntryView | null
  quality?: QualityBadgeView | null
  subscribed?: boolean
  /** 操作回调 */
  onEdit?: (asset: AssetSummary) => void
  onRetire?: (asset: AssetSummary) => void
  onReconcile?: (asset: AssetSummary) => void
  onSubscribe?: (asset: AssetSummary) => void
  onUnsubscribe?: (asset: AssetSummary) => void
}

export function AssetCard({
  asset,
  expanded,
  onToggle,
  detail,
  lineage,
  quality,
  subscribed,
  onEdit,
  onRetire,
  onReconcile,
  onSubscribe,
  onUnsubscribe,
}: AssetCardProps) {
  const t = useTranslations("assetCatalog")

  const handleToggle = useCallback(() => {
    onToggle(asset.id)
  }, [asset.id, onToggle])

  const sensVar = sensitivityVariant(asset.sensitivity)
  const statVar = statusVariant(asset.status)
  const updatedAt = detail?.updatedAt ?? detail?.createdAt

  return (
    <motion.div
      layout
      className={cn(
        "rounded-lg border bg-card p-4 cursor-pointer transition-colors duration-200",
        "hover:border-ring",
        expanded && "border-primary bg-accent/40",
      )}
      onClick={handleToggle}
      role="button"
      tabIndex={0}
      aria-expanded={expanded}
      onKeyDown={(e: React.KeyboardEvent) => { if (e.key === "Enter" || e.key === " ") handleToggle() }}
    >
      {/* Card header — always visible */}
      <div className="flex items-start gap-3">
        <div className="flex size-8 shrink-0 items-center justify-center rounded-md bg-muted">
          <HugeiconsIcon icon={Database01Icon} className="size-4 text-muted-foreground" />
        </div>
        <div className="min-w-0 flex-1">
          <div className="font-semibold text-sm truncate" title={asset.name || asset.qualifiedName}>
            {asset.name || asset.qualifiedName}
          </div>
          <div className="text-xs text-muted-foreground truncate" title={asset.qualifiedName}>
            {asset.qualifiedName}
          </div>
        </div>
        <div className="flex shrink-0 items-center gap-1.5">
          <Badge variant={sensVar}>{asset.sensitivity}</Badge>
          <Badge variant={statVar}>{asset.status}</Badge>
        </div>
      </div>

      {/* Meta row — always visible */}
      <div className="mt-2.5 flex items-center gap-4 text-xs text-muted-foreground">
        {asset.ownerId != null && <span className="text-xs">👤 {asset.ownerId}</span>}
        {updatedAt && (
          <span className="text-xs">{new Date(updatedAt).toLocaleDateString()}</span>
        )}
        {asset.tags.length > 0 && (
          <div className="flex gap-1 flex-wrap">
            {asset.tags.slice(0, 3).map((tag) => (
              <span key={tag} className="rounded bg-muted px-1.5 py-0.5 text-[10px]">{tag}</span>
            ))}
          </div>
        )}
        {/* Lineage ref hint */}
        {detail?.lineageTableRef && (
          <span className="text-xs text-muted-foreground truncate max-w-32">
            → {detail.lineageTableRef}
          </span>
        )}
      </div>

      {/* Expanded detail area */}
      <AnimatePresence initial={false}>
        {expanded && (
          <motion.div
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: "auto", opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={{ duration: 0.2, ease: "easeInOut" }}
            className="overflow-hidden"
          >
            <div className="mt-3 border-t border-border pt-3 space-y-3">
              {/* STALE warning */}
              {asset.status === "STALE" && (
                <div className="flex items-center gap-2 rounded border border-warning/30 bg-warning/10 px-3 py-2 text-xs text-warning">
                  ⚠ {t("staleHint")}
                </div>
              )}

              {/* Description */}
              {detail?.description && (
                <div>
                  <div className="text-[10px] font-semibold uppercase text-muted-foreground mb-1">
                    {t("detailDescription")}
                  </div>
                  <p className="text-xs text-muted-foreground line-clamp-3">
                    {detail.description}
                  </p>
                </div>
              )}

              {/* Lineage — lineage.available=true 时展示, false/null 时不展示 */}
              {lineage != null && lineage.available && (
                <div>
                  <div className="text-[10px] font-semibold uppercase text-muted-foreground mb-1">
                    {t("detailLineage")}
                  </div>
                  <p className="text-xs text-muted-foreground truncate">
                    {lineage.tableRef
                      ? `${lineage.tableRef} → ${asset.qualifiedName}`
                      : `${t("lineageSource")}: ${asset.qualifiedName}`
                    }
                    {lineage.upstreamCount > 0 && ` · ↑${lineage.upstreamCount}`}
                    {lineage.downstreamCount > 0 && ` ↓${lineage.downstreamCount}`}
                  </p>
                </div>
              )}

              {/* Quality — quality.available=true 时展示 */}
              {quality != null && quality.available && (
                <div>
                  <div className="text-[10px] font-semibold uppercase text-muted-foreground mb-1">
                    {t("detailQuality")}
                  </div>
                  <div className="flex items-center gap-2">
                    {quality.score != null && (
                      <Badge variant={
                        quality.score >= 80 ? "success" : quality.score >= 60 ? "warning" : "destructive"
                      }>
                        {t("qualityScore")}: {quality.score}
                      </Badge>
                    )}
                    {quality.grade && (
                      <span className="text-xs text-muted-foreground">{quality.grade}</span>
                    )}
                  </div>
                </div>
              )}

              {/* Metadata tags */}
              {(detail?.ownerId != null || detail?.stewardId != null || detail?.tags.length) && (
                <div>
                  <div className="text-[10px] font-semibold uppercase text-muted-foreground mb-1">
                    {t("detailMetadata")}
                  </div>
                  <div className="flex flex-wrap gap-1.5 text-xs text-muted-foreground">
                    {detail?.ownerId != null && <span className="rounded bg-muted px-2 py-0.5">{t("ownerIdLabel")}: {detail.ownerId}</span>}
                    {detail?.stewardId != null && <span className="rounded bg-muted px-2 py-0.5">{t("stewardIdLabel")}: {detail.stewardId}</span>}
                    {(detail?.tags ?? asset.tags).map((tag: string) => (
                      <span key={tag} className="rounded bg-muted px-2 py-0.5">{tag}</span>
                    ))}
                  </div>
                </div>
              )}

              {/* Actions */}
              <div className="flex flex-wrap gap-1.5 pt-1">
                {onEdit && (
                  <Button size="sm" variant="ghost" onClick={(e: React.MouseEvent) => { e.stopPropagation(); onEdit(asset); }}>
                    <HugeiconsIcon icon={Edit02Icon} className="size-3.5" />
                    {t("editAction")}
                  </Button>
                )}
                {onRetire && (
                  <Button size="sm" variant="ghost" onClick={(e: React.MouseEvent) => { e.stopPropagation(); onRetire(asset); }}>
                    <HugeiconsIcon icon={Delete02Icon} className="size-3.5" />
                    {t("retireAction")}
                  </Button>
                )}
                {onReconcile && (
                  <Button size="sm" variant="ghost" onClick={(e: React.MouseEvent) => { e.stopPropagation(); onReconcile(asset); }}>
                    <HugeiconsIcon icon={RefreshIcon} className="size-3.5" />
                    {t("reconcileAction")}
                  </Button>
                )}
                {subscribed
                  ? onUnsubscribe && (
                      <Button size="sm" variant="ghost" onClick={(e: React.MouseEvent) => { e.stopPropagation(); onUnsubscribe(asset); }}>
                        <HugeiconsIcon icon={Notification01Icon} className="size-3.5" />
                        {t("subscribed")}
                      </Button>
                    )
                  : onSubscribe && (
                      <Button size="sm" variant="ghost" onClick={(e: React.MouseEvent) => { e.stopPropagation(); onSubscribe(asset); }}>
                        <HugeiconsIcon icon={Notification01Icon} className="size-3.5" />
                        {t("subscribe")}
                      </Button>
                    )
                }
              </div>
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </motion.div>
  )
}
