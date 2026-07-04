"use client"

import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import { TickDouble02Icon, Alert02Icon, Clock01Icon, CancelCircleIcon } from "@hugeicons/core-free-icons"

import { ResourceBar } from "@/components/ui/resource-bar"
import type { FreshnessDashboard } from "@/lib/workspace/freshness-api"

function trendText(t: (k: string, v?: Record<string, number>) => string, delta: number): string {
  if (delta === 0) return t("trendFlat")
  if (delta > 0) return t("trendUp", { pct: delta })
  return t("trendDown", { pct: Math.abs(delta) })
}

export function FreshnessSummaryStrip({
  dashboard,
  selectedTier,
  onTierClick,
}: {
  dashboard: FreshnessDashboard | null
  selectedTier: string | null
  onTierClick: (tier: string) => void
}) {
  const t = useTranslations("freshness")

  if (!dashboard) {
    return (
      <div className="flex items-center justify-center py-8 text-sm text-muted-foreground">
        {t("emptyTitle")}
      </div>
    )
  }

  const { summary, trend } = dashboard
  const healthyPct = summary.total > 0 ? (summary.fresh / summary.total) * 100 : 0

  const tiers = [
    { key: "FRESH", label: t("summaryFresh"), count: summary.fresh, icon: TickDouble02Icon, color: "text-success" },
    { key: "AGING", label: t("summaryAging"), count: summary.aging, icon: Alert02Icon, color: "text-warning" },
    { key: "STALE", label: t("summaryStale"), count: summary.stale, icon: Clock01Icon, color: "text-destructive" },
    { key: "NEVER", label: t("summaryNever"), count: summary.never, icon: CancelCircleIcon, color: "text-destructive" },
  ]

  return (
    <div className="flex flex-col gap-3">
      {/* 4 stat cards in a row */}
      <div className="grid grid-cols-4 gap-2.5">
        {tiers.map((tier) => {
          const isSelected = selectedTier === tier.key
          return (
            <button
              key={tier.key}
              type="button"
              onClick={() => onTierClick(tier.key)}
              className={`flex items-center gap-3 rounded-lg border bg-card p-3 text-left transition-colors hover:bg-muted/50 ${
                isSelected ? "ring-1 ring-muted-foreground/40" : ""
              }`}
            >
              <div className={`flex size-9 shrink-0 items-center justify-center rounded-lg ${tier.color}/10`}>
                <HugeiconsIcon icon={tier.icon} className={`size-4 ${tier.color}`} />
              </div>
              <div className="min-w-0 flex-1">
                <div className="text-xs text-muted-foreground">{tier.label}</div>
                <div className="flex items-baseline gap-1.5">
                  <span className="text-xl font-semibold tabular-nums">{tier.count}</span>
                  {trend && (
                    <span className="text-xs text-muted-foreground">
                      {trendTextForTier(t, trend, tier.key)}
                    </span>
                  )}
                </div>
              </div>
            </button>
          )
        })}
      </div>

      {/* Health progress bar */}
      <div className="rounded-lg border bg-card p-4">
        <ResourceBar
          label={t("healthBar")}
          value={healthyPct}
          threshold={70}
          highIsBad={false}
          formatValue={(pct) => `${pct.toFixed(1)}%`}
        />
        {/* Distribution text */}
        {summary.total > 0 && (
          <p className="mt-2 text-xs text-muted-foreground">
            {t("distribution", {
              freshPct: ((summary.fresh / summary.total) * 100).toFixed(1),
              agingPct: ((summary.aging / summary.total) * 100).toFixed(1),
              stalePct: ((summary.stale / summary.total) * 100).toFixed(1),
              neverPct: ((summary.never / summary.total) * 100).toFixed(1),
            })}
          </p>
        )}
      </div>
    </div>
  )
}

function trendTextForTier(
  t: (k: string, v?: Record<string, number>) => string,
  trend: NonNullable<FreshnessDashboard["trend"]>,
  tier: string,
): string {
  switch (tier) {
    case "FRESH": return trendText(t, trend.freshDelta)
    case "AGING": return trendText(t, trend.agingDelta)
    case "STALE": return trendText(t, trend.staleDelta)
    default: return ""
  }
}
