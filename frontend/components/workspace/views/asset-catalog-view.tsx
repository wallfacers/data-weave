"use client"

/**
 * 数据资产目录视图（023 US1/US2/US5 + 029 写侧收口）：左分面搜索 + 中列表 + 右详情。
 * 029 新增：编目/编辑/下线/对账操作、可点击 owner 分面、分页、我的订阅入口。
 * 血缘/质量「懒加载 + 降级安全」：不可达隐藏入口，不阻断主功能（SC-002）。
 */

import { useCallback, useEffect, useState } from "react"
import { useTranslations } from "next-intl"
import { toast } from "sonner"
import { HugeiconsIcon } from "@hugeicons/react"
import { Search01Icon, Database01Icon, Shield01Icon, GitBranchIcon, Notification01Icon, PlusSignIcon, Settings01Icon } from "@hugeicons/core-free-icons"
import { Button } from "@/components/ui/button"
import {
  searchAssets,
  fetchAsset,
  fetchAssetLineage,
  fetchAssetQuality,
  subscribe,
  retireAsset,
  reconcileAsset,
  type AssetSummary,
  type AssetDetail,
  type LineageEntryView,
  type QualityBadgeView,
  type SearchResult,
} from "@/lib/catalog-api"
import { AssetDialog, RetireConfirmDialog } from "./asset-dialog"
import { SubscriptionListDialog } from "./subscription-dialog"

const SENSITIVITY_TONE: Record<string, string> = {
  PUBLIC: "text-muted-foreground",
  INTERNAL: "text-foreground",
  CONFIDENTIAL: "text-amber-600",
  PII: "text-red-600",
}

const PAGE_SIZE = 20

export function AssetCatalogView() {
  const t = useTranslations("assetCatalog")

  // search state
  const [keyword, setKeyword] = useState("")
  const [sensitivity, setSensitivity] = useState("")
  const [owner, setOwner] = useState("")
  const [page, setPage] = useState(1)
  const [result, setResult] = useState<SearchResult | null>(null)
  const [loading, setLoading] = useState(false)

  // detail state
  const [selected, setSelected] = useState<AssetDetail | null>(null)
  const [lineage, setLineage] = useState<LineageEntryView | null>(null)
  const [quality, setQuality] = useState<QualityBadgeView | null>(null)

  // dialog state (029)
  const [createOpen, setCreateOpen] = useState(false)
  const [editOpen, setEditOpen] = useState(false)
  const [retireOpen, setRetireOpen] = useState(false)
  const [subListOpen, setSubListOpen] = useState(false)

  const runSearch = useCallback(async () => {
    setLoading(true)
    try {
      const res = await searchAssets({ keyword, sensitivity, owner, page, size: PAGE_SIZE })
      setResult(res.data ?? null)
    } finally {
      setLoading(false)
    }
  }, [keyword, sensitivity, owner, page])

  useEffect(() => {
    void runSearch()
  }, [runSearch])

  // reset page when filter changes
  useEffect(() => { setPage(1) }, [keyword, sensitivity, owner])

  const openDetail = useCallback(async (a: AssetSummary) => {
    const res = await fetchAsset(a.id)
    if (res.code !== 0 || !res.data) {
      toast.error(res.message ?? t("loadFailed"))
      return
    }
    setSelected(res.data)
    setLineage(null)
    setQuality(null)
    void fetchAssetLineage(a.id).then((r) => setLineage(r.data ?? null))
    void fetchAssetQuality(a.id).then((r) => setQuality(r.data ?? null))
  }, [t])

  // ─── write actions (029) ──────────────────────────────────────

  const doSubscribe = useCallback(async () => {
    if (!selected) return
    const res = await subscribe({ targetType: "ASSET", targetId: selected.id, changeFilter: "schema,quality,freshness" })
    if (res.code === 0 && res.data?.outcome === "EXECUTED") {
      toast.success(t("subscribed"))
    } else if (res.data?.outcome === "PENDING_APPROVAL") {
      toast.info(t("pendingApproval"))
    } else {
      toast.error(res.message ?? t("actionFailed"))
    }
  }, [selected, t])

  const doRetire = useCallback(async () => {
    if (!selected) return
    try {
      const res = await retireAsset(selected.id)
      if (res.data?.outcome === "EXECUTED") {
        toast.success(t("retired") ?? "Asset retired")
        setSelected(null)
        runSearch()
      } else if (res.data?.outcome === "PENDING_APPROVAL") {
        toast.info(t("pendingApproval"))
      } else {
        toast.error(res.message ?? t("actionFailed"))
      }
    } catch { toast.error(t("actionFailed")) }
    setRetireOpen(false)
  }, [selected, t, runSearch])

  const doReconcile = useCallback(async () => {
    if (!selected) return
    try {
      const res = await reconcileAsset(selected.id)
      if (res.data?.outcome === "EXECUTED") {
        toast.success("对账完成")
        // refresh detail
        openDetail({ id: selected.id } as AssetSummary)
      } else if (res.data?.outcome === "PENDING_APPROVAL") {
        toast.info(t("pendingApproval"))
      } else {
        toast.error(res.message ?? t("actionFailed"))
      }
    } catch { toast.error(t("actionFailed")) }
  }, [selected, t, openDetail])

  // ─── facets ───────────────────────────────────────────────────

  const facets = result?.facets ?? {}
  const totalPages = Math.max(1, Math.ceil((result?.total ?? 0) / PAGE_SIZE))

  const facetButton = (key: string, label: string, current: string, onClick: (v: string) => void) => (
    <button
      key={key}
      type="button"
      onClick={() => onClick(current === key ? "" : key)}
      className={`flex justify-between rounded px-2 py-1 text-left text-sm ${current === key ? "bg-accent" : "hover:bg-accent/50"}`}
    >
      <span>{label}</span>
      <span className="text-muted-foreground">{(facets.owner ?? {})[key] ?? ""}</span>
    </button>
  )

  return (
    <div className="flex h-full min-h-0">
      {/* 左：分面搜索 */}
      <aside className="flex w-64 shrink-0 flex-col gap-4 border-r border-border p-4">
        <div className="flex items-center gap-2 rounded-md border border-input px-2">
          <HugeiconsIcon icon={Search01Icon} className="size-4 text-muted-foreground" />
          <input
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            placeholder={t("searchPlaceholder")}
            className="h-9 w-full bg-transparent text-sm outline-none"
          />
        </div>
        <div>
          <div className="mb-2 text-xs font-medium text-muted-foreground">{t("facetSensitivity")}</div>
          <div className="flex flex-col gap-1">
            <button
              type="button"
              onClick={() => setSensitivity("")}
              className={`flex justify-between rounded px-2 py-1 text-left text-sm ${sensitivity === "" ? "bg-accent" : "hover:bg-accent/50"}`}
            >
              <span>{t("all")}</span>
            </button>
            {Object.entries(facets.sensitivity ?? {}).map(([k, c]) => (
              <button
                key={k}
                type="button"
                onClick={() => setSensitivity(k === sensitivity ? "" : k)}
                className={`flex justify-between rounded px-2 py-1 text-left text-sm ${sensitivity === k ? "bg-accent" : "hover:bg-accent/50"}`}
              >
                <span className={SENSITIVITY_TONE[k] ?? ""}>{k}</span>
                <span className="text-muted-foreground">{c}</span>
              </button>
            ))}
          </div>
        </div>
        {/* 029: clickable owner facets */}
        {Object.keys(facets.owner ?? {}).length > 0 && (
          <div>
            <div className="mb-2 text-xs font-medium text-muted-foreground">{t("facetOwner")}</div>
            <div className="flex flex-col gap-1">
              {Object.entries(facets.owner ?? {}).map(([k]) =>
                facetButton(k, `#${k}`, owner, setOwner)
              )}
            </div>
          </div>
        )}
        {/* 029: quality score filter placeholder */}
        <div>
          <div className="mb-2 text-xs font-medium text-muted-foreground">{t("qualityFilter") ?? "质量分数"}</div>
          <div className="text-xs text-muted-foreground">{t("qualityFilterHint") ?? "质量数据来自 022 评分卡，当前环境可能为空"}</div>
        </div>
      </aside>

      {/* 中：列表 */}
      <section className="flex min-w-0 flex-1 flex-col">
        <div className="flex items-center justify-between border-b border-border px-4 py-2 text-sm">
          <span className="font-medium">{t("title")}</span>
          <div className="flex items-center gap-2">
            {/* 029: create + subscriptions */}
            <Button variant="ghost" size="sm" onClick={() => setSubListOpen(true)}>
              {t("mySubscriptions") ?? "我的订阅"}
            </Button>
            <Button size="sm" onClick={() => setCreateOpen(true)}>
              <HugeiconsIcon icon={PlusSignIcon} className="size-4" />
              <span className="ml-1">{t("create") ?? "编目资产"}</span>
            </Button>
            <span className="text-muted-foreground">
              {loading ? t("loading") : t("totalCount", { count: result?.total ?? 0 })}
              {result?.truncated ? ` · ${t("truncated")}` : ""}
            </span>
          </div>
        </div>
        <div className="min-h-0 flex-1 overflow-auto">
          {(result?.items ?? []).map((a) => (
            <button
              key={a.id}
              type="button"
              onClick={() => openDetail(a)}
              className={`flex w-full items-center gap-3 border-b border-border/50 px-4 py-3 text-left hover:bg-accent/40 ${selected?.id === a.id ? "bg-accent/60" : ""}`}
            >
              <HugeiconsIcon icon={Database01Icon} className="size-4 shrink-0 text-muted-foreground" />
              <div className="min-w-0 flex-1">
                <div className="truncate text-sm font-medium">{a.name || a.qualifiedName}</div>
                <div className="truncate text-xs text-muted-foreground">{a.qualifiedName}</div>
              </div>
              <span className={`text-xs ${SENSITIVITY_TONE[a.sensitivity] ?? ""}`}>{a.sensitivity}</span>
              {a.status !== "ACTIVE" && (
                <span className="rounded bg-amber-100 px-1.5 py-0.5 text-xs text-amber-700">{a.status}</span>
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

      {/* 右：详情 */}
      <aside className="flex w-80 shrink-0 flex-col border-l border-border">
        {selected ? (
          <div className="flex min-h-0 flex-1 flex-col overflow-auto p-4">
            <div className="flex items-center justify-between">
              <div className="mb-1 text-base font-semibold">{selected.name || selected.qualifiedName}</div>
              {/* 029: edit button */}
              <Button variant="ghost" size="sm" onClick={() => setEditOpen(true)}>
                <HugeiconsIcon icon={Settings01Icon} className="size-4" />
              </Button>
            </div>
            <div className="mb-3 text-xs text-muted-foreground">{selected.qualifiedName}</div>

            {selected.status !== "ACTIVE" && (
              <div className="mb-3 rounded border border-amber-300 bg-amber-50 px-2 py-1 text-xs text-amber-700">
                {t("staleHint")}
              </div>
            )}

            <dl className="space-y-2 text-sm">
              <Row label={t("owner")} value={selected.ownerId ? `#${selected.ownerId}` : "—"} />
              <Row label={t("steward")} value={selected.stewardId ? `#${selected.stewardId}` : "—"} />
              <Row label={t("sensitivity")} value={selected.sensitivity} valueClass={SENSITIVITY_TONE[selected.sensitivity]} />
              <Row label={t("status")} value={selected.status} />
            </dl>

            {selected.description && (
              <p className="mt-3 text-sm text-muted-foreground">{selected.description}</p>
            )}

            {selected.tags.length > 0 && (
              <div className="mt-3 flex flex-wrap gap-1">
                {selected.tags.map((tg) => (
                  <span key={tg} className="rounded bg-secondary px-2 py-0.5 text-xs text-secondary-foreground">{tg}</span>
                ))}
              </div>
            )}

            {/* 血缘入口 */}
            {lineage && lineage.available && !lineage.degraded && (
              <div className="mt-4 flex items-center gap-2 rounded-md border border-border px-3 py-2 text-sm">
                <HugeiconsIcon icon={GitBranchIcon} className="size-4 text-muted-foreground" />
                <span>{t("lineage")}</span>
                <span className="ml-auto text-xs text-muted-foreground">
                  ↑{lineage.upstreamCount} ↓{lineage.downstreamCount}
                </span>
              </div>
            )}

            {/* 质量徽章 */}
            {quality && quality.available && !quality.degraded && quality.score != null && (
              <div className="mt-2 flex items-center gap-2 rounded-md border border-border px-3 py-2 text-sm">
                <HugeiconsIcon icon={Shield01Icon} className="size-4 text-emerald-600" />
                <span>{t("quality")}</span>
                <span className="ml-auto text-xs">{quality.grade ?? quality.score}</span>
              </div>
            )}

            {/* 029: action buttons row */}
            <div className="mt-4 flex flex-col gap-2">
              <button
                type="button"
                onClick={doSubscribe}
                className="flex items-center justify-center gap-2 rounded-md bg-primary px-3 py-2 text-sm text-primary-foreground hover:bg-primary/90"
              >
                <HugeiconsIcon icon={Notification01Icon} className="size-4" />
                {t("subscribe")}
              </button>
              <div className="flex gap-2">
                <Button variant="outline" size="sm" className="flex-1" onClick={doReconcile}>
                  {t("reconcile") ?? "对账"}
                </Button>
                <Button variant="destructive" size="sm" className="flex-1" onClick={() => setRetireOpen(true)}>
                  {t("retire") ?? "下线"}
                </Button>
              </div>
            </div>
          </div>
        ) : (
          <div className="flex flex-1 items-center justify-center p-8 text-center text-sm text-muted-foreground">
            {t("selectHint")}
          </div>
        )}
      </aside>

      {/* 029 dialogs */}
      <AssetDialog
        open={createOpen}
        mode="create"
        onClose={() => setCreateOpen(false)}
        onSaved={runSearch}
      />
      {selected && (
        <>
          <AssetDialog
            open={editOpen}
            mode="edit"
            asset={selected}
            onClose={() => setEditOpen(false)}
            onSaved={() => { runSearch(); openDetail({ id: selected.id } as AssetSummary); }}
          />
          <RetireConfirmDialog
            open={retireOpen}
            assetName={selected.name ?? selected.qualifiedName}
            onClose={() => setRetireOpen(false)}
            onConfirm={doRetire}
          />
        </>
      )}
      <SubscriptionListDialog
        open={subListOpen}
        onClose={() => setSubListOpen(false)}
      />
    </div>
  )
}

function Row({ label, value, valueClass }: { label: string; value: string; valueClass?: string }) {
  return (
    <div className="flex justify-between gap-3">
      <dt className="text-muted-foreground">{label}</dt>
      <dd className={`text-right ${valueClass ?? ""}`}>{value}</dd>
    </div>
  )
}
