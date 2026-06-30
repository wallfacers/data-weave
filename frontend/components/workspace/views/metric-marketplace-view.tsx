"use client"

/**
 * 指标市场视图（023 US3 + 029 收口）：搜索上架指标 + 详情 + 认证 + 复用 + 029 上架/下架。
 */

import { useCallback, useEffect, useState } from "react"
import { useTranslations } from "next-intl"
import { toast } from "sonner"
import { HugeiconsIcon } from "@hugeicons/react"
import { Search01Icon, Analytics01Icon, CheckmarkBadge01Icon, ArrowReloadHorizontalIcon, PlusSignIcon, Delete01Icon } from "@hugeicons/core-free-icons"
import { Button } from "@/components/ui/button"
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import { DropdownSelect } from "@/components/ui/select"
import {
  searchListings,
  fetchListing,
  certifyMetric,
  reuseMetric,
  publishMetric,
  unpublishMetric,
  type ListingSummary,
  type MarketplaceDetail,
  type ListingSearchResult,
  type GateResult,
} from "@/lib/catalog-api"

const PAGE_SIZE = 20

function handleGate(r: GateResult, okMsg: string) {
  switch (r.outcome) {
    case "EXECUTED": toast.success(okMsg); return true;
    case "PENDING_APPROVAL": toast.info(r.message ?? "Submitted for approval"); return true;
    default: toast.error(r.message ?? "Action failed"); return false;
  }
}

export function MetricMarketplaceView() {
  const t = useTranslations("metricMarketplace")

  const [keyword, setKeyword] = useState("")
  const [certification, setCertification] = useState("")
  const [page, setPage] = useState(1)
  const [result, setResult] = useState<ListingSearchResult | null>(null)
  const [loading, setLoading] = useState(false)
  const [selected, setSelected] = useState<MarketplaceDetail | null>(null)

  // 029: publish/unpublish dialogs
  const [publishOpen, setPublishOpen] = useState(false)
  const [reuseOpen, setReuseOpen] = useState(false)

  const runSearch = useCallback(async () => {
    setLoading(true)
    try {
      const res = await searchListings({ keyword, certification, page, size: PAGE_SIZE })
      setResult(res.data ?? null)
    } finally {
      setLoading(false)
    }
  }, [keyword, certification, page])

  useEffect(() => { void runSearch() }, [runSearch])
  useEffect(() => { setPage(1) }, [keyword, certification])

  const openDetail = useCallback(async (m: ListingSummary) => {
    const res = await fetchListing(m.id)
    if (res.code === 0 && res.data) setSelected(res.data)
    else toast.error(res.message ?? t("loadFailed"))
  }, [t])

  const doCertify = useCallback(async () => {
    if (!selected) return
    const res = await certifyMetric(selected.detail.id)
    if (handleGate(res.data, t("done")))
      void openDetail(selected.detail as unknown as ListingSummary)
  }, [selected, t, openDetail])

  const doReuse = useCallback(async (consumerRef: string) => {
    if (!selected || !consumerRef.trim()) return
    const res = await reuseMetric(selected.detail.id, { consumerType: "METRIC", consumerRef: consumerRef.trim() })
    if (handleGate(res.data, "复用成功")) setReuseOpen(false)
  }, [selected])

  // 029: unpublish
  const doUnpublish = useCallback(async () => {
    if (!selected) return
    const res = await unpublishMetric(selected.detail.id)
    if (handleGate(res.data, t("unpublished") ?? "已下架")) {
      setSelected(null)
      runSearch()
    }
  }, [selected, t, runSearch])

  const facets = result?.facets ?? {}
  const totalPages = Math.max(1, Math.ceil((result?.total ?? 0) / PAGE_SIZE))

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
          {/* 029: certification facet */}
          <DropdownSelect
            value={certification}
            onChange={(v) => setCertification(v ?? "")}
            options={[
              { value: "", label: t("all") ?? "全部" },
              { value: "CERTIFIED", label: t("certified") },
              { value: "NONE", label: "NONE" },
            ]}
          />
          {/* 029: publish button */}
          <Button size="sm" onClick={() => setPublishOpen(true)}>
            <HugeiconsIcon icon={PlusSignIcon} className="size-4" />
            <span className="ml-1">{t("publish") ?? "上架"}</span>
          </Button>
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
        {/* 029: pagination */}
        {totalPages > 1 && (
          <div className="flex items-center justify-between border-t border-border px-4 py-2 text-sm">
            <span className="text-muted-foreground">第 {page} / {totalPages} 页</span>
            <div className="flex gap-1">
              <Button variant="outline" size="sm" disabled={page <= 1} onClick={() => setPage((p) => p - 1)}>上一页</Button>
              <Button variant="outline" size="sm" disabled={page >= totalPages} onClick={() => setPage((p) => p + 1)}>下一页</Button>
            </div>
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
            </div>
            {selected.detail.description && (
              <p className="mt-1 text-sm text-muted-foreground">{selected.detail.description}</p>
            )}

            <dl className="mt-3 space-y-2 text-sm">
              <Row label={t("metricType")} value={selected.detail.metricType} />
              <Row label={t("owner")} value={selected.detail.ownerId ? `#${selected.detail.ownerId}` : "—"} />
              <Row label={t("freshness")} value={selected.detail.freshnessInfo ?? "—"} />
              <Row label={t("reuseCount")} value={String(selected.detail.reuseCount)} />
              <Row label="Status" value={selected.detail.status} />
            </dl>

            {Object.keys(selected.detail.definition ?? {}).length > 0 && (
              <div className="mt-3 rounded-md border border-border p-3">
                <div className="mb-1 text-xs font-medium text-muted-foreground">{t("definition")}</div>
                <pre className="overflow-auto text-xs">{JSON.stringify(selected.detail.definition, null, 2)}</pre>
              </div>
            )}

            {selected.lineage.available && !selected.lineage.degraded && (
              <div className="mt-2 text-xs text-muted-foreground">
                {t("lineageSources", { count: selected.lineage.upstreamCount })}
              </div>
            )}

            <div className="mt-4 flex flex-col gap-2">
              <div className="flex gap-2">
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
                  onClick={() => setReuseOpen(true)}
                  className="flex flex-1 items-center justify-center gap-2 rounded-md border border-input px-3 py-2 text-sm hover:bg-accent"
                >
                  <HugeiconsIcon icon={ArrowReloadHorizontalIcon} className="size-4" />
                  {t("reuse")}
                </button>
              </div>
              {/* 029: unpublish */}
              {selected.detail.status !== "DELISTED" && (
                <Button variant="destructive" size="sm" onClick={doUnpublish}>
                  <HugeiconsIcon icon={Delete01Icon} className="size-4" />
                  <span className="ml-1">{t("unpublish") ?? "下架"}</span>
                </Button>
              )}
            </div>
          </div>
        ) : (
          <div className="flex flex-1 items-center justify-center p-8 text-center text-sm text-muted-foreground">
            {t("selectHint")}
          </div>
        )}
      </aside>

      {/* 029: publish dialog */}
      <PublishDialog open={publishOpen} onClose={() => setPublishOpen(false)} onPublished={runSearch} />

      {/* 029: reuse dialog */}
      <ReuseDialog open={reuseOpen} onClose={() => setReuseOpen(false)} onConfirm={doReuse} />
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

// ─── 029: publish dialog ────────────────────────────────────────

function PublishDialog({ open, onClose, onPublished }: { open: boolean; onClose: () => void; onPublished: () => void }) {
  const [metricType, setMetricType] = useState("ATOMIC")
  const [metricId, setMetricId] = useState("")
  const [description, setDescription] = useState("")
  const [saving, setSaving] = useState(false)

  async function doPublish() {
    if (!metricId.trim()) { toast.error("指标 ID 不能为空"); return }
    setSaving(true)
    try {
      const res = await publishMetric({ metricType, metricId: Number(metricId), description: description.trim() || undefined })
      if (handleGate(res.data, "指标已上架")) { onPublished(); onClose() }
    } catch { toast.error("操作失败") }
    finally { setSaving(false) }
  }

  return (
    <Dialog open={open} onOpenChange={(o) => { if (!o) onClose(); }}>
      <DialogContent className="sm:max-w-sm">
        <DialogHeader>
          <DialogTitle>上架指标</DialogTitle>
          <DialogDescription>从既有指标定义中选择并上架到市场</DialogDescription>
        </DialogHeader>
        <div className="grid gap-4 py-4">
          <div className="grid grid-cols-4 items-center gap-4">
            <label className="text-right text-sm">类型</label>
            <DropdownSelect className="col-span-3" value={metricType} onChange={(v) => setMetricType(v || "ATOMIC")}
              options={[{ value: "ATOMIC", label: "ATOMIC" }, { value: "DERIVED", label: "DERIVED" }]} />
          </div>
          <div className="grid grid-cols-4 items-center gap-4">
            <label className="text-right text-sm">指标 ID *</label>
            <Input className="col-span-3" value={metricId} onChange={(e) => setMetricId(e.target.value)} placeholder="数字" />
          </div>
          <div className="grid grid-cols-4 items-center gap-4">
            <label className="text-right text-sm">描述</label>
            <Input className="col-span-3" value={description} onChange={(e) => setDescription(e.target.value)} />
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose} disabled={saving}>取消</Button>
          <Button onClick={doPublish} disabled={saving}>{saving ? "提交中…" : "上架"}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

// ─── 029: reuse dialog ──────────────────────────────────────────

function ReuseDialog({ open, onClose, onConfirm }: { open: boolean; onClose: () => void; onConfirm: (ref: string) => void }) {
  const [consumerRef, setConsumerRef] = useState("")
  return (
    <Dialog open={open} onOpenChange={(o) => { if (!o) onClose(); }}>
      <DialogContent className="sm:max-w-sm">
        <DialogHeader>
          <DialogTitle>复用指标</DialogTitle>
          <DialogDescription>复用会形成依赖关系；成环将被服务端拒绝并给出提示</DialogDescription>
        </DialogHeader>
        <div className="py-4">
          <Input value={consumerRef} onChange={(e) => setConsumerRef(e.target.value)} placeholder="消费方引用（如指标编码）" />
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>取消</Button>
          <Button onClick={() => onConfirm(consumerRef)} disabled={!consumerRef.trim()}>确认复用</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
