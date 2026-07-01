"use client"

/**
 * 指标市场视图（023 US3 + 029 US2 写侧收口）：搜索上架指标 + 详情 + 复用 + 认证。
 * 029：列表头「上架指标」;详情「下架」(确认式);复用改 Dialog(替换 window.prompt),
 * 三态如实(resolveGate/gateToast);`catalog.reuse_cycle` 专门「循环依赖」提示(FR-007)。
 */

import { useCallback, useEffect, useState } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  Search01Icon,
  Analytics01Icon,
  CheckmarkBadge01Icon,
  ArrowReloadHorizontalIcon,
  Add01Icon,
  Delete02Icon,
} from "@hugeicons/core-free-icons"
import {
  searchListings,
  fetchListing,
  certifyMetric,
  reuseMetric,
  listMetric,
  delistMetric,
  type ListingSummary,
  type MarketplaceDetail,
  type ListingSearchResult,
} from "@/lib/catalog-api"
import { useProjectContext } from "@/lib/project-context"
import { resolveGate, gateToast } from "@/lib/gate-outcome"
import { Button } from "@/components/ui/button"
import { Pagination } from "@/components/ui/pagination"
import { ConfirmDialog } from "@/components/workspace/views/shared/confirm-dialog"
import { MetricListingDialog } from "@/components/workspace/views/metric/metric-listing-dialog"
import { MetricReuseDialog } from "@/components/workspace/views/metric/metric-reuse-dialog"

/** 已知业务错误码 → 友好文案 key（SC-006）。reuse_cycle 专门提示循环依赖（FR-007）。 */
const PAGE_SIZE = 20

const METRIC_ERR_KEYS: Record<string, string> = {
  "catalog.reuse_cycle": "errReuseCycle",
  "catalog.reuse_invalid": "errReuseInvalid",
  "catalog.listing_invalid": "errListingInvalid",
  "catalog.not_certifiable": "errNotCertifiable",
}

export function MetricMarketplaceView() {
  const t = useTranslations("metricMarketplace")
  // 当前项目：切换后触发重新检索（FR-014/SC-007）
  const projectId = useProjectContext((s) => s.currentProjectId)

  const [keyword, setKeyword] = useState("")
  const [certification, setCertification] = useState("")
  const [page, setPage] = useState(1)
  const [result, setResult] = useState<ListingSearchResult | null>(null)
  const [loading, setLoading] = useState(false)
  const [selected, setSelected] = useState<MarketplaceDetail | null>(null)
  const [toast, setToast] = useState<string | null>(null)

  // 029 写侧
  const [listOpen, setListOpen] = useState(false)
  const [reuseOpen, setReuseOpen] = useState(false)
  const [delistOpen, setDelistOpen] = useState(false)
  const [delistBusy, setDelistBusy] = useState(false)

  const runSearch = useCallback(async () => {
    setLoading(true)
    try {
      const res = await searchListings({ keyword, certification: certification || undefined, page, size: PAGE_SIZE })
      setResult(res.data ?? null)
    } finally {
      setLoading(false)
    }
  }, [keyword, certification, page, projectId])

  useEffect(() => {
    void runSearch()
  }, [runSearch])

  useEffect(() => {
    if (!toast) return
    const id = setTimeout(() => setToast(null), 3000)
    return () => clearTimeout(id)
  }, [toast])

  const openDetail = useCallback(async (m: ListingSummary) => {
    const res = await fetchListing(m.id)
    if (res.code === 0 && res.data) setSelected(res.data)
    else setToast(res.message ?? t("loadFailed"))
  }, [t])

  const refreshDetail = useCallback(async (id: number) => {
    const res = await fetchListing(id)
    if (res.code === 0 && res.data) setSelected(res.data)
  }, [])

  const doCertify = useCallback(async () => {
    if (!selected) return
    const r = resolveGate(await certifyMetric(selected.detail.id))
    setToast(gateToast(r, t, METRIC_ERR_KEYS))
    if (r.kind !== "failed") void refreshDetail(selected.detail.id)
  }, [selected, t, refreshDetail])

  const submitList = useCallback(async (payload: Record<string, unknown>) => {
    const r = resolveGate(await listMetric(payload as Parameters<typeof listMetric>[0]))
    setToast(gateToast(r, t, METRIC_ERR_KEYS))
    if (r.kind !== "failed") {
      void runSearch()
      return true
    }
    return false
  }, [t, runSearch])

  const submitReuse = useCallback(async (body: { consumerType: string; consumerRef: string }) => {
    if (!selected) return false
    const r = resolveGate(await reuseMetric(selected.detail.id, body))
    setToast(gateToast(r, t, METRIC_ERR_KEYS))
    if (r.kind !== "failed") {
      void refreshDetail(selected.detail.id)
      return true
    }
    return false
  }, [selected, t, refreshDetail])

  const doDelist = useCallback(async () => {
    if (!selected) return
    setDelistBusy(true)
    const r = resolveGate(await delistMetric(selected.detail.id))
    setToast(gateToast(r, t, METRIC_ERR_KEYS))
    setDelistBusy(false)
    setDelistOpen(false)
    if (r.kind !== "failed") {
      void runSearch()
      void refreshDetail(selected.detail.id)
    }
  }, [selected, t, runSearch, refreshDetail])

  const isDelisted = selected?.detail.status === "DELISTED"

  return (
    <div className="flex h-full min-h-0">
      <section className="flex min-w-0 flex-1 flex-col border-r border-border">
        <div className="flex items-center gap-2 border-b border-border px-4 py-2">
          <HugeiconsIcon icon={Search01Icon} className="size-4 text-muted-foreground" />
          <input
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            placeholder={t("searchPlaceholder")}
            className="h-8 w-full bg-transparent text-sm outline-none"
          />
          <span className="shrink-0 text-xs text-muted-foreground">
            {loading ? t("loading") : t("totalCount", { count: result?.total ?? 0 })}
          </span>
          <Button size="sm" onClick={() => setListOpen(true)}>
            <HugeiconsIcon icon={Add01Icon} className="size-4" />
            {t("listAction")}
          </Button>
        </div>
        {/* certification 分面过滤（T031） */}
        <div className="flex items-center gap-1 border-b border-border px-4 py-1.5 text-xs">
          <span className="mr-1 text-muted-foreground">{t("facetCertification")}</span>
          {["", "CERTIFIED", "NONE"].map((c) => (
            <button
              key={c || "ALL"}
              type="button"
              onClick={() => { setCertification(c); setPage(1) }}
              className={`rounded px-2 py-0.5 ${certification === c ? "bg-accent" : "hover:bg-accent/50"}`}
            >
              {c === "" ? t("all") : c === "CERTIFIED" ? t("certified") : t("uncertified")}
            </button>
          ))}
        </div>
        <div className="min-h-0 flex-1 overflow-auto">
          {(result?.items ?? []).map((m) => (
            <button
              key={m.id}
              type="button"
              onClick={() => openDetail(m)}
              className={`flex w-full items-center gap-3 border-b border-border/50 px-4 py-3 text-left hover:bg-accent/40 ${selected?.detail.id === m.id ? "bg-accent/60" : ""}`}
            >
              <HugeiconsIcon icon={Analytics01Icon} className="size-4 shrink-0 text-muted-foreground" />
              <div className="min-w-0 flex-1">
                <div className="truncate text-sm font-medium">{m.metricCode ?? `#${m.metricId}`}</div>
                <div className="truncate text-xs text-muted-foreground">{m.metricType} · {m.freshnessInfo ?? "—"}</div>
              </div>
              {m.status === "DELISTED" && (
                <span className="rounded bg-muted px-1.5 py-0.5 text-xs text-muted-foreground">{m.status}</span>
              )}
              {m.certification === "CERTIFIED" && (
                <span className="flex items-center gap-1 text-xs text-emerald-600">
                  <HugeiconsIcon icon={CheckmarkBadge01Icon} className="size-4" />
                  {t("certified")}
                </span>
              )}
            </button>
          ))}
          {!loading && (result?.items.length ?? 0) === 0 && (
            <div className="p-8 text-center text-sm text-muted-foreground">{t("empty")}</div>
          )}
        </div>
        {(result?.total ?? 0) > PAGE_SIZE && (
          <div className="border-t border-border px-4 py-2">
            <Pagination
              page={page}
              size={PAGE_SIZE}
              total={result?.total ?? 0}
              totalPages={Math.max(1, Math.ceil((result?.total ?? 0) / PAGE_SIZE))}
              onPageChange={setPage}
            />
          </div>
        )}
      </section>

      <aside className="flex w-96 shrink-0 flex-col">
        {selected ? (
          <div className="flex min-h-0 flex-1 flex-col overflow-auto p-4">
            <div className="flex items-center gap-2">
              <span className="text-base font-semibold">{selected.detail.metricCode ?? `#${selected.detail.metricId}`}</span>
              {selected.detail.certification === "CERTIFIED" && (
                <span className="flex items-center gap-1 text-xs text-emerald-600">
                  <HugeiconsIcon icon={CheckmarkBadge01Icon} className="size-4" />
                  {t("certified")}
                </span>
              )}
              {isDelisted && (
                <span className="rounded bg-muted px-1.5 py-0.5 text-xs text-muted-foreground">{selected.detail.status}</span>
              )}
            </div>
            {selected.detail.description && (
              <p className="mt-1 text-sm text-muted-foreground">{selected.detail.description}</p>
            )}

            <dl className="mt-3 space-y-2 text-sm">
              <Row label={t("metricType")} value={selected.detail.metricType} />
              <Row label={t("owner")} value={selected.detail.ownerId ? `#${selected.detail.ownerId}` : "—"} />
              <Row label={t("freshness")} value={selected.detail.freshnessInfo ?? "—"} />
              <Row label={t("reuseCount")} value={String(selected.detail.reuseCount)} />
            </dl>

            {Object.keys(selected.detail.definition ?? {}).length > 0 && (
              <div className="mt-3 rounded-md border border-border p-3">
                <div className="mb-1 text-xs font-medium text-muted-foreground">{t("definition")}</div>
                <pre className="overflow-auto text-xs">{JSON.stringify(selected.detail.definition, null, 2)}</pre>
              </div>
            )}

            {/* 血缘（降级隐藏） */}
            {selected.lineage.available && !selected.lineage.degraded && (
              <div className="mt-2 text-xs text-muted-foreground">
                {t("lineageSources", { count: selected.lineage.upstreamCount })}
              </div>
            )}

            <div className="mt-4 flex gap-2">
              <button
                type="button"
                onClick={doCertify}
                disabled={selected.detail.certification === "CERTIFIED" || isDelisted}
                className="flex flex-1 items-center justify-center gap-2 rounded-md border border-input px-3 py-2 text-sm hover:bg-accent disabled:opacity-50"
              >
                <HugeiconsIcon icon={CheckmarkBadge01Icon} className="size-4" />
                {t("certify")}
              </button>
              <button
                type="button"
                onClick={() => setReuseOpen(true)}
                className="flex flex-1 items-center justify-center gap-2 rounded-md border border-input px-3 py-2 text-sm hover:bg-accent"
              >
                <HugeiconsIcon icon={ArrowReloadHorizontalIcon} className="size-4" />
                {t("reuse")}
              </button>
            </div>
            {!isDelisted && (
              <Button variant="outline" className="mt-2" onClick={() => setDelistOpen(true)}>
                <HugeiconsIcon icon={Delete02Icon} className="size-4" />
                {t("delistAction")}
              </Button>
            )}
          </div>
        ) : (
          <div className="flex flex-1 items-center justify-center p-8 text-center text-sm text-muted-foreground">
            {t("selectHint")}
          </div>
        )}
      </aside>

      {/* 上架 */}
      <MetricListingDialog open={listOpen} onOpenChange={setListOpen} onSubmit={submitList} />
      {/* 复用 */}
      <MetricReuseDialog open={reuseOpen} onOpenChange={setReuseOpen} onSubmit={submitReuse} />
      {/* 下架确认 */}
      <ConfirmDialog
        open={delistOpen}
        onOpenChange={(o) => !o && setDelistOpen(false)}
        title={t("delistConfirmTitle")}
        description={t("delistConfirmDesc")}
        confirmLabel={t("delistAction")}
        destructive
        busy={delistBusy}
        onConfirm={doDelist}
      />

      {toast && (
        <div className="fixed bottom-4 right-4 z-50 rounded-md bg-foreground px-3 py-2 text-sm text-background shadow-lg" role="status">
          {toast}
        </div>
      )}
    </div>
  )
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex justify-between gap-3">
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="text-right">{value}</dd>
    </div>
  )
}
