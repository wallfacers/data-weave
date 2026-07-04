"use client"

/**
 * 数据资产目录视图 — 043 重设计：顶部 filter toolbar + 卡片网格 + 内联展开详情。
 * 029 写侧能力（编目/编辑/下线/对账/订阅）全部保留，接入闸门三态，零功能回退。
 */

import { useCallback, useEffect, useState, useMemo } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import { Add01Icon } from "@hugeicons/core-free-icons"
import { toast } from "sonner"

import {
  searchAssets,
  fetchAsset,
  fetchAssetLineage,
  fetchAssetQuality,
  subscribe,
  unsubscribe,
  listSubscriptions,
  createAsset,
  updateAsset,
  retireAsset,
  reconcileAsset,
  type AssetSummary,
  type AssetDetail,
  type AssetSubscription,
  type LineageEntryView,
  type QualityBadgeView,
  type SearchResult,
} from "@/lib/catalog-api"
import { listDatasources } from "@/lib/datasource-api"
import { useProjectContext } from "@/lib/project-context"
import { resolveGate } from "@/lib/gate-outcome"
import { findAssetSubscription } from "@/lib/subscriptions"
import {
  INITIAL_QUERY,
  toggleFacet,
  setKeyword as setQueryKeyword,
  setPage as setQueryPage,
  buildSearchParams,
  type AssetQueryState,
} from "@/lib/asset-search-query"
import { Button } from "@/components/ui/button"
import { LoadingState } from "@/components/ui/loading-state"
import { Pagination } from "@/components/ui/pagination"
import { AssetCard } from "@/components/workspace/views/asset/asset-card"
import { AssetFilterToolbar } from "@/components/workspace/views/asset/asset-filter-toolbar"
import type { FilterState } from "@/components/workspace/views/asset/asset-filter-toolbar"
import { AssetFormDialog } from "@/components/workspace/views/asset/asset-form-dialog"
import { SubscriptionsDialog } from "@/components/workspace/views/asset/subscriptions-dialog"
import { ConfirmDialog } from "@/components/workspace/views/shared/confirm-dialog"

const PAGE_SIZE = 20

/** 已知业务错误码 → 友好文案 key */
const ASSET_ERR_KEYS: Record<string, string> = {
  "catalog.duplicate_asset": "errDuplicateAsset",
  "catalog.asset_invalid": "errAssetInvalid",
  "catalog.forbidden_sensitivity": "errForbiddenSensitivity",
}

export function AssetCatalogView() {
  const t = useTranslations("assetCatalog")
  const projectId = useProjectContext((s) => s.currentProjectId)

  // ── 搜索 & 筛选 ──
  const [query, setQuery] = useState<AssetQueryState>(INITIAL_QUERY)
  const [result, setResult] = useState<SearchResult | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const filterState = useMemo<FilterState>(() => ({
    sensitivity: query.sensitivity ?? "",
    owner: query.owner ?? "",
    keyword: query.keyword ?? "",
  }), [query])

  const handleFilterChange = useCallback((next: FilterState) => {
    let q = { ...query }
    if (next.keyword !== filterState.keyword) {
      q = setQueryKeyword(q, next.keyword)
    }
    if (next.sensitivity !== filterState.sensitivity) {
      q = toggleFacet(q, "sensitivity", next.sensitivity ? next.sensitivity : query.sensitivity || "")
    }
    if (next.owner !== filterState.owner) {
      q = toggleFacet(q, "owner", next.owner ? next.owner : query.owner || "")
    }
    setQuery(q)
  }, [query, filterState])

  const handleClearFilters = useCallback(() => {
    setQuery(INITIAL_QUERY)
  }, [])

  const runSearch = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const res = await searchAssets({ ...buildSearchParams(query), size: PAGE_SIZE })
      setResult(res.data ?? null)
    } catch {
      setError(t("loadFailed"))
    } finally {
      setLoading(false)
    }
  }, [query, t])

  useEffect(() => { void runSearch() }, [runSearch])

  // ── 展开卡片 ──
  const [expandedId, setExpandedId] = useState<number | null>(null)
  const [detail, setDetail] = useState<AssetDetail | null>(null)
  const [lineage, setLineage] = useState<LineageEntryView | null>(null)
  const [quality, setQuality] = useState<QualityBadgeView | null>(null)
  const [subscriptions, setSubscriptions] = useState<AssetSubscription[]>([])

  const handleToggle = useCallback(async (id: number) => {
    if (expandedId === id) {
      setExpandedId(null)
      setDetail(null)
      setLineage(null)
      setQuality(null)
      return
    }
    setExpandedId(id)
    setDetail(null)
    setLineage(null)
    setQuality(null)
    const res = await fetchAsset(id)
    if (res.code === 0 && res.data) setDetail(res.data)
    void fetchAssetLineage(id).then((r) => { if (r.data) setLineage(r.data) })
    void fetchAssetQuality(id).then((r) => { if (r.data) setQuality(r.data) })
  }, [expandedId])

  const loadSubscriptions = useCallback(async () => {
    const res = await listSubscriptions()
    setSubscriptions(res.data ?? [])
  }, [])

  useEffect(() => { void loadSubscriptions() }, [loadSubscriptions])

  // ── 写操作 (029 gate → sonner toast) ──
  const [datasources, setDatasources] = useState<{ id: number; name: string }[]>([])
  const [createOpen, setCreateOpen] = useState(false)
  const [editOpen, setEditOpen] = useState(false)
  const [editingAsset, setEditingAsset] = useState<AssetSummary | null>(null)
  const [confirm, setConfirm] = useState<null | { kind: "retire" | "reconcile"; asset: AssetSummary }>(null)
  const [confirmBusy, setConfirmBusy] = useState(false)
  const [subsOpen, setSubsOpen] = useState(false)

  const openCreate = useCallback(async () => {
    setCreateOpen(true)
    if (datasources.length === 0) {
      try {
        const ds = await listDatasources()
        setDatasources(ds.map((d) => ({ id: d.id, name: d.name })))
      } catch { /* 降级: Select 空态 */ }
    }
  }, [datasources.length])

  const handleEdit = useCallback((asset: AssetSummary) => {
    setEditingAsset(asset)
    setEditOpen(true)
  }, [])

  const submitCreate = useCallback(async (payload: Record<string, unknown>) => {
    const r = resolveGate(await createAsset(payload))
    if (r.kind === "executed") { toast.success(r.message); void runSearch(); return true }
    if (r.kind === "pending") { toast.info(r.message); return true }
    toast.error(r.message)
    return false
  }, [t, runSearch])

  const submitEdit = useCallback(async (payload: Record<string, unknown>) => {
    if (!editingAsset) return false
    if (Object.keys(payload).length === 0) {
      toast(t("noChanges"))
      return true
    }
    const r = resolveGate(await updateAsset(editingAsset.id, payload))
    if (r.kind !== "failed") { toast.success(r.message); void runSearch(); return true }
    toast.error(r.message)
    return false
  }, [editingAsset, t, runSearch])

  const handleRetire = useCallback((asset: AssetSummary) => {
    setConfirm({ kind: "retire", asset })
  }, [])

  const handleReconcile = useCallback((asset: AssetSummary) => {
    setConfirm({ kind: "reconcile", asset })
  }, [])

  const handleSubscribe = useCallback(async (asset: AssetSummary) => {
    const r = resolveGate(await subscribe(asset.id))
    if (r.kind !== "failed") { toast.success(r.message); void loadSubscriptions() }
    else toast.error(r.message)
  }, [loadSubscriptions])

  const handleUnsubscribe = useCallback(async (asset: AssetSummary) => {
    const sub = findAssetSubscription(subscriptions, asset.id)
    if (!sub) return
    const r = resolveGate(await unsubscribe(sub.id))
    if (r.kind !== "failed") { toast.success(r.message); void loadSubscriptions() }
    else toast.error(r.message)
  }, [subscriptions, loadSubscriptions])

  const executeConfirm = useCallback(async () => {
    if (!confirm) return
    setConfirmBusy(true)
    try {
      const { kind, asset } = confirm
      const r = resolveGate(kind === "retire" ? await retireAsset(asset.id) : await reconcileAsset(asset.id))
      if (r.kind !== "failed") { toast.success(r.message); void runSearch() }
      else toast.error(r.message)
    } finally {
      setConfirmBusy(false)
      setConfirm(null)
    }
  }, [confirm, runSearch])

  // ── 渲染 ──
  const facets = result?.facets ?? {}
  const subscribedAssetId = expandedId != null ? findAssetSubscription(subscriptions, expandedId) : null

  return (
    <div className="flex min-h-0 min-w-0 flex-1 flex-col p-5">
      {/* Toolbar */}
      <div className="shrink-0 pb-4">
        <AssetFilterToolbar
          facets={{ sensitivity: facets.sensitivity, owner: facets.owner }}
          filter={filterState}
          onFilterChange={handleFilterChange}
          onClear={handleClearFilters}
          rightSlot={
            <>
              <Button size="sm" variant="ghost" onClick={() => setSubsOpen(true)}>
                {t("mySubscriptions")}
              </Button>
              <Button size="sm" onClick={openCreate}>
                <HugeiconsIcon icon={Add01Icon} className="size-4" />
                {t("createAction")}
              </Button>
            </>
          }
        />
        {result != null && !loading && (
          <p className="mt-2 text-xs text-muted-foreground">
            {t("totalCount", { count: result.total })}
          </p>
        )}
      </div>

      {/* Card grid */}
      <div className="min-h-0 flex-1 overflow-auto">
        {loading && <LoadingState />}

        {!loading && error && (
          <div className="flex flex-col items-center gap-3 py-12">
            <p className="text-sm text-muted-foreground">{error}</p>
            <Button variant="outline" size="sm" onClick={runSearch}>{t("errorRetry")}</Button>
          </div>
        )}

        {!loading && !error && result != null && result.items.length === 0 && (
          <div className="flex flex-col items-center gap-3 py-12">
            <p className="text-sm text-muted-foreground">{t("emptyFiltered")}</p>
            <Button variant="ghost" size="sm" onClick={handleClearFilters}>{t("filterClear")}</Button>
          </div>
        )}

        {!loading && !error && (result?.items.length ?? 0) > 0 && (
          <div
            className="grid gap-4"
            style={{ gridTemplateColumns: "repeat(auto-fill, minmax(380px, 1fr))" }}
          >
            {result!.items.map((asset) => (
              <AssetCard
                key={asset.id}
                asset={asset}
                expanded={expandedId === asset.id}
                onToggle={handleToggle}
                detail={expandedId === asset.id ? detail : null}
                lineage={expandedId === asset.id ? lineage : null}
                quality={expandedId === asset.id ? quality : null}
                subscribed={subscribedAssetId != null}
                onEdit={handleEdit}
                onRetire={handleRetire}
                onReconcile={handleReconcile}
                onSubscribe={handleSubscribe}
                onUnsubscribe={handleUnsubscribe}
              />
            ))}
          </div>
        )}
      </div>

      {/* Pagination */}
      {result != null && result.total > PAGE_SIZE && (
        <div className="shrink-0 border-t border-border pt-3">
          <Pagination
            page={query.page}
            size={PAGE_SIZE}
            total={result.total}
            totalPages={Math.max(1, Math.ceil(result.total / PAGE_SIZE))}
            onPageChange={(p) => setQuery((q) => setQueryPage(q, p))}
          />
        </div>
      )}

      {/* Dialogs */}
      {createOpen && (
        <AssetFormDialog
          open={createOpen}
          onOpenChange={setCreateOpen}
          mode="create"
          datasources={datasources}
          onSubmit={submitCreate}
        />
      )}
      {editOpen && editingAsset && (
        <AssetFormDialog
          open={editOpen}
          onOpenChange={setEditOpen}
          mode="edit"
          asset={editingAsset}
          datasources={datasources}
          onSubmit={submitEdit}
        />
      )}
      {confirm != null && (
        <ConfirmDialog
          open
          onOpenChange={(o: boolean) => { if (!o) setConfirm(null) }}
          title={confirm.kind === "retire" ? t("retireConfirmTitle") : t("reconcileConfirmTitle")}
          description={confirm.kind === "retire" ? t("retireConfirmDesc") : t("reconcileConfirmDesc")}
          confirmLabel={confirm.kind === "retire" ? t("retireAction") : t("reconcileAction")}
          onConfirm={executeConfirm}
          busy={confirmBusy}
        />
      )}
      {subsOpen && (
        <SubscriptionsDialog
          open={subsOpen}
          onOpenChange={setSubsOpen}
        />
      )}
    </div>
  )
}
