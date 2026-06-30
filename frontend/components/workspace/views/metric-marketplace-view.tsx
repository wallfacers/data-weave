"use client"

/**
 * 指标市场视图（023 US3）：搜索上架指标 + 详情（定义 + 血缘降级 + 认证）+ 复用 + 认证（L2 可能待审批）。
 */

import { useCallback, useEffect, useState } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import { Search01Icon, Analytics01Icon, CheckmarkBadge01Icon, ArrowReloadHorizontalIcon } from "@hugeicons/core-free-icons"
import {
  searchListings,
  fetchListing,
  certifyMetric,
  reuseMetric,
  type ListingSummary,
  type MarketplaceDetail,
  type ListingSearchResult,
} from "@/lib/catalog-api"

export function MetricMarketplaceView() {
  const t = useTranslations("metricMarketplace")

  const [keyword, setKeyword] = useState("")
  const [result, setResult] = useState<ListingSearchResult | null>(null)
  const [loading, setLoading] = useState(false)
  const [selected, setSelected] = useState<MarketplaceDetail | null>(null)
  const [toast, setToast] = useState<string | null>(null)

  const runSearch = useCallback(async () => {
    setLoading(true)
    try {
      const res = await searchListings({ keyword })
      setResult(res.data ?? null)
    } finally {
      setLoading(false)
    }
  }, [keyword])

  useEffect(() => {
    void runSearch()
  }, [runSearch])

  const openDetail = useCallback(async (m: ListingSummary) => {
    const res = await fetchListing(m.id)
    if (res.code === 0 && res.data) setSelected(res.data)
    else setToast(res.message ?? t("loadFailed"))
  }, [t])

  const handleGate = useCallback((code: number, outcome?: string, message?: string) => {
    if (code === 0 && outcome === "EXECUTED") setToast(t("done"))
    else if (outcome === "PENDING_APPROVAL") setToast(t("pendingApproval"))
    else setToast(message ?? t("actionFailed"))
  }, [t])

  const doCertify = useCallback(async () => {
    if (!selected) return
    const res = await certifyMetric(selected.detail.id)
    handleGate(res.code, res.data?.outcome, res.message)
    if (res.data?.outcome === "EXECUTED") void openDetail(selected.detail as unknown as ListingSummary)
  }, [selected, handleGate, openDetail])

  const doReuse = useCallback(async () => {
    if (!selected) return
    const consumerRef = window.prompt(t("reusePrompt"))
    if (!consumerRef) return
    const res = await reuseMetric(selected.detail.id, { consumerType: "METRIC", consumerRef })
    handleGate(res.code, res.data?.outcome, res.message)
  }, [selected, handleGate, t])

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
                disabled={selected.detail.certification === "CERTIFIED"}
                className="flex flex-1 items-center justify-center gap-2 rounded-md border border-input px-3 py-2 text-sm hover:bg-accent disabled:opacity-50"
              >
                <HugeiconsIcon icon={CheckmarkBadge01Icon} className="size-4" />
                {t("certify")}
              </button>
              <button
                type="button"
                onClick={doReuse}
                className="flex flex-1 items-center justify-center gap-2 rounded-md border border-input px-3 py-2 text-sm hover:bg-accent"
              >
                <HugeiconsIcon icon={ArrowReloadHorizontalIcon} className="size-4" />
                {t("reuse")}
              </button>
            </div>
          </div>
        ) : (
          <div className="flex flex-1 items-center justify-center p-8 text-center text-sm text-muted-foreground">
            {t("selectHint")}
          </div>
        )}
      </aside>

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
