"use client"

import { useMemo } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import { FilterIcon, Cancel01Icon, Search01Icon } from "@hugeicons/core-free-icons"

import { cn } from "@/lib/utils"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"

export interface FilterState {
  sensitivity: string
  owner: string
  keyword: string
}

interface FacetEntries {
  sensitivity?: Record<string, number>
  owner?: Record<string, number>
  tag?: Record<string, number>
}

interface AssetFilterToolbarProps {
  facets: FacetEntries
  filter: FilterState
  onFilterChange: (next: FilterState) => void
  onClear: () => void
  rightSlot?: React.ReactNode
}

export function AssetFilterToolbar({
  facets,
  filter,
  onFilterChange,
  onClear,
  rightSlot,
}: AssetFilterToolbarProps) {
  const t = useTranslations("assetCatalog")

  const sensitivityOptions = useMemo(() => {
    const opts = [{ value: "", label: t("all") }]
    if (facets.sensitivity) {
      for (const [k, c] of Object.entries(facets.sensitivity)) {
        opts.push({ value: k, label: `${k} (${c})` })
      }
    }
    return opts
  }, [facets.sensitivity, t])

  const ownerOptions = useMemo(() => {
    const opts = [{ value: "", label: t("all") }]
    if (facets.owner) {
      for (const [k, c] of Object.entries(facets.owner)) {
        opts.push({ value: k, label: `${k} (${c})` })
      }
    }
    return opts
  }, [facets.owner, t])

  const hasActive = filter.sensitivity !== "" || filter.owner !== "" || filter.keyword !== ""
  const activeCount = (filter.sensitivity ? 1 : 0) + (filter.owner ? 1 : 0) + (filter.keyword ? 1 : 0)

  return (
    <div className="flex shrink-0 flex-col gap-2">
      <div className="flex flex-wrap items-center gap-2">
        <HugeiconsIcon icon={FilterIcon} className="size-4 shrink-0 text-muted-foreground" />

        {/* Sensitivity segmented */}
        <div className="inline-flex h-8 items-center rounded-md border border-input bg-background p-0.5">
          {sensitivityOptions.map((o) => (
            <button
              key={o.value}
              type="button"
              onClick={() => onFilterChange({ ...filter, sensitivity: filter.sensitivity === o.value ? "" : o.value })}
              className={cn(
                "h-7 rounded px-2 text-xs transition-colors",
                filter.sensitivity === o.value || (o.value === "" && filter.sensitivity === "")
                  ? "bg-muted font-medium text-foreground"
                  : "text-muted-foreground hover:text-foreground",
              )}
            >
              {o.label}
            </button>
          ))}
        </div>

        {/* Owner segmented */}
        {ownerOptions.length > 1 && (
          <div className="inline-flex h-8 items-center rounded-md border border-input bg-background p-0.5">
            {ownerOptions.map((o) => (
              <button
                key={o.value}
                type="button"
                onClick={() => onFilterChange({ ...filter, owner: filter.owner === o.value ? "" : o.value })}
                className={cn(
                  "h-7 rounded px-2 text-xs transition-colors",
                  filter.owner === o.value || (o.value === "" && filter.owner === "")
                    ? "bg-muted font-medium text-foreground"
                    : "text-muted-foreground hover:text-foreground",
                )}
              >
                {o.label}
              </button>
            ))}
          </div>
        )}

        {/* Search */}
        <div className={cn("relative", "w-48")}>
          <HugeiconsIcon
            icon={Search01Icon}
            className="pointer-events-none absolute left-2 top-1/2 size-3.5 -translate-y-1/2 text-muted-foreground"
          />
          <Input
            className="h-8 pl-7 text-sm"
            placeholder={t("searchPlaceholder")}
            value={filter.keyword}
            onChange={(e) => onFilterChange({ ...filter, keyword: e.target.value })}
          />
        </div>

        {/* Clear */}
        {hasActive && (
          <Button variant="ghost" size="sm" className="h-8 text-xs" onClick={onClear}>
            <HugeiconsIcon icon={Cancel01Icon} className="size-3.5" />
            {t("filterClear")} {activeCount > 0 && `· ${activeCount}`}
          </Button>
        )}

        {/* Right slot */}
        {rightSlot && <div className="ml-auto flex items-center gap-2">{rightSlot}</div>}
      </div>
    </div>
  )
}
