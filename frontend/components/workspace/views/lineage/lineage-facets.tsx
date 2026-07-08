"use client"

/**
 * 054 US3：血缘探索器左栏「分面」入口——数据源 / 分层 / 最近 三分面切换。
 *
 * 搜索为主入口（US1 hero），左栏降级为**辅助分面浏览**：
 * - **数据源**：复用 {@link LineageTree}（已修 052 占位——数据源展开出真实表，非列）。
 * - **分层**：ODS/DWD/DWS/ADS 跨数据源聚合，展开出该层表（同层跨库以数据源名区分）。
 * - **最近**：会话本地锚定过的资产（无 ownership、无后端；{@link getRecentAssets}）。
 *
 * 详情面板（LineageDetailPanel）与本组件正交、零耦合——分面只负责「选资产 → onSelect 锚图」。
 *
 * 设计约束（DESIGN.md）：Segmented 原语、语义 token、hugeicons、gap-* / size-* 间距、不手写 dark:。
 */
import { useCallback, useEffect, useState } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  Database01Icon,
  Layers01Icon,
  Clock01Icon,
  ArrowDown01Icon,
  ArrowRight01Icon,
  Table01Icon,
} from "@hugeicons/core-free-icons"
import { Segmented } from "@/components/ui/segmented"
import { DwScroll } from "@/components/ui/dw-scroll"
import { LineageTree } from "@/components/workspace/views/lineage/lineage-tree"
import type { GraphNodeView } from "@/lib/lineage-api"
import { fetchTablesByLayer } from "@/lib/lineage-api"
import { getRecentAssets, type RecentAsset } from "@/lib/workspace/lineage-recent"

type Facet = "datasource" | "layer" | "recent"

/** 分层枚举（数据术语，保持英文）。 */
const LAYERS = ["ODS", "DWD", "DWS", "ADS"] as const

export interface LineageFacetsProps {
  onSelect?: (node: GraphNodeView) => void
}

export function LineageFacets({ onSelect }: LineageFacetsProps) {
  const t = useTranslations("lineageView")
  const [facet, setFacet] = useState<Facet>("datasource")

  return (
    <div className="flex h-full flex-col">
      <div className="shrink-0 px-2 pt-2">
        <Segmented
          className="w-full"
          ariaLabel={t("facetSwitch")}
          value={facet}
          onChange={(v) => setFacet(v as Facet)}
          options={[
            { value: "datasource", label: t("facetDatasource"), icon: Database01Icon },
            { value: "layer", label: t("facetLayer"), icon: Layers01Icon },
            { value: "recent", label: t("facetRecent"), icon: Clock01Icon },
          ]}
        />
      </div>
      <div className="min-h-0 flex-1">
        {facet === "datasource" && <LineageTree onSelect={onSelect} />}
        {facet === "layer" && <LayerFacet onSelect={onSelect} />}
        {facet === "recent" && <RecentFacet onSelect={onSelect} />}
      </div>
    </div>
  )
}

/** 分层分面：每层懒加载其表（跨数据源）。 */
function LayerFacet({ onSelect }: LineageFacetsProps) {
  const t = useTranslations("lineageView")
  const [openLayer, setOpenLayer] = useState<string | null>(null)
  const [tablesByLayer, setTablesByLayer] = useState<Record<string, GraphNodeView[]>>({})
  const [loading, setLoading] = useState<string | null>(null)

  const toggle = useCallback(
    async (layer: string) => {
      if (openLayer === layer) {
        setOpenLayer(null)
        return
      }
      setOpenLayer(layer)
      if (!tablesByLayer[layer]) {
        setLoading(layer)
        try {
          const res = await fetchTablesByLayer(layer, 0, 200)
          if (res.code === 0 && res.data) {
            setTablesByLayer((prev) => ({ ...prev, [layer]: res.data ?? [] }))
          }
        } catch {
          /* silent：保持空 */
        } finally {
          setLoading(null)
        }
      }
    },
    [openLayer, tablesByLayer],
  )

  return (
    <DwScroll className="h-full" innerClassName="py-1">
      {LAYERS.map((layer) => {
        const open = openLayer === layer
        const tables = tablesByLayer[layer] ?? []
        return (
          <div key={layer}>
            <button
              type="button"
              className="flex w-full items-center gap-1.5 rounded-md px-2 py-1.5 text-left text-sm transition-colors hover:bg-muted/50"
              onClick={() => toggle(layer)}
            >
              {loading === layer ? (
                <span className="size-4 shrink-0 animate-spin text-muted-foreground">⟳</span>
              ) : (
                <HugeiconsIcon
                  icon={open ? ArrowDown01Icon : ArrowRight01Icon}
                  className="size-4 shrink-0 text-muted-foreground"
                />
              )}
              <HugeiconsIcon icon={Layers01Icon} className="size-4 shrink-0 text-muted-foreground" />
              <span className="font-medium">{layer}</span>
              {open && tables.length > 0 && (
                <span className="ml-auto shrink-0 text-xs text-muted-foreground">{tables.length}</span>
              )}
            </button>
            {open &&
              tables.map((tb) => (
                <button
                  key={tb.id}
                  type="button"
                  className="flex w-full items-center gap-1.5 rounded-md py-1.5 pr-2 pl-8 text-left text-sm transition-colors hover:bg-muted/50"
                  onClick={() => onSelect?.(tb)}
                >
                  <HugeiconsIcon icon={Table01Icon} className="size-4 shrink-0 text-muted-foreground" />
                  <span className="truncate">{tb.name}</span>
                  {typeof tb.attrs?.datasourceName === "string" && (
                    <span className="ml-auto shrink-0 truncate text-[10px] text-muted-foreground">
                      {tb.attrs.datasourceName as string}
                    </span>
                  )}
                </button>
              ))}
            {open && !loading && tables.length === 0 && (
              <div className="py-1 pl-8 text-xs text-muted-foreground">{t("layerEmpty")}</div>
            )}
          </div>
        )
      })}
    </DwScroll>
  )
}

/** 最近分面：会话本地锚定记录（点击重锚）。 */
function RecentFacet({ onSelect }: LineageFacetsProps) {
  const t = useTranslations("lineageView")
  const [items, setItems] = useState<RecentAsset[]>([])

  // sessionStorage 非响应式：挂载时读一次（切到本分面即重挂载 → 读最新）
  useEffect(() => {
    setItems(getRecentAssets())
  }, [])

  if (items.length === 0) {
    return <div className="px-4 py-8 text-center text-sm text-muted-foreground">{t("recentEmpty")}</div>
  }
  return (
    <DwScroll className="h-full" innerClassName="py-1">
      {items.map((a) => (
        <button
          key={a.id}
          type="button"
          className="flex w-full items-center gap-1.5 rounded-md px-2 py-1.5 text-left text-sm transition-colors hover:bg-muted/50"
          onClick={() => onSelect?.({ id: a.id, type: a.type as GraphNodeView["type"], name: a.name })}
        >
          <HugeiconsIcon icon={Table01Icon} className="size-4 shrink-0 text-muted-foreground" />
          <span className="truncate">{a.name}</span>
          {a.datasourceName && (
            <span className="ml-auto shrink-0 truncate text-[10px] text-muted-foreground">{a.datasourceName}</span>
          )}
        </button>
      ))}
    </DwScroll>
  )
}
