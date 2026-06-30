"use client"

/**
 * 数据资产目录视图（023 US1/US2/US5 + 029 写侧收口）：左分面搜索 + 中列表 + 右详情。
 * 029：列表头「编目资产」;详情「编辑/下线/对账」(过闸门,三态如实);血缘/质量懒加载降级安全。
 */

import { useCallback, useEffect, useState } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  Search01Icon,
  Database01Icon,
  Shield01Icon,
  GitBranchIcon,
  Notification01Icon,
  Add01Icon,
  Edit02Icon,
  Delete02Icon,
  RefreshIcon,
} from "@hugeicons/core-free-icons"
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
import { resolveGate, gateToast } from "@/lib/gate-outcome"
import { findAssetSubscription } from "@/lib/subscriptions"
import { Button } from "@/components/ui/button"
import { AssetFormDialog } from "@/components/workspace/views/asset/asset-form-dialog"
import { SubscriptionsDialog } from "@/components/workspace/views/asset/subscriptions-dialog"
import { ConfirmDialog } from "@/components/workspace/views/shared/confirm-dialog"

const SENSITIVITY_TONE: Record<string, string> = {
  PUBLIC: "text-muted-foreground",
  INTERNAL: "text-foreground",
  CONFIDENTIAL: "text-amber-600",
  PII: "text-red-600",
}

/** 已知业务错误码 → 友好文案 key（SC-006）。 */
const ASSET_ERR_KEYS: Record<string, string> = {
  "catalog.duplicate_asset": "errDuplicateAsset",
  "catalog.asset_invalid": "errAssetInvalid",
  "catalog.forbidden_sensitivity": "errForbiddenSensitivity",
}

export function AssetCatalogView() {
  const t = useTranslations("assetCatalog")

  const [keyword, setKeyword] = useState("")
  const [sensitivity, setSensitivity] = useState("")
  const [result, setResult] = useState<SearchResult | null>(null)
  const [loading, setLoading] = useState(false)
  const [selected, setSelected] = useState<AssetDetail | null>(null)
  const [lineage, setLineage] = useState<LineageEntryView | null>(null)
  const [quality, setQuality] = useState<QualityBadgeView | null>(null)
  const [toast, setToast] = useState<string | null>(null)

  // 029 写侧
  const [datasources, setDatasources] = useState<{ id: number; name: string }[]>([])
  const [createOpen, setCreateOpen] = useState(false)
  const [editOpen, setEditOpen] = useState(false)
  const [confirm, setConfirm] = useState<null | "retire" | "reconcile">(null)
  const [confirmBusy, setConfirmBusy] = useState(false)
  const [subscriptions, setSubscriptions] = useState<AssetSubscription[]>([])
  const [subsOpen, setSubsOpen] = useState(false)

  const runSearch = useCallback(async () => {
    setLoading(true)
    try {
      const res = await searchAssets({ keyword, sensitivity })
      setResult(res.data ?? null)
    } finally {
      setLoading(false)
    }
  }, [keyword, sensitivity])

  useEffect(() => {
    void runSearch()
  }, [runSearch])

  useEffect(() => {
    if (!toast) return
    const id = setTimeout(() => setToast(null), 3000)
    return () => clearTimeout(id)
  }, [toast])

  const refreshDetail = useCallback(async (id: number) => {
    const res = await fetchAsset(id)
    if (res.code === 0 && res.data) setSelected(res.data)
  }, [])

  const loadSubscriptions = useCallback(async () => {
    const res = await listSubscriptions()
    setSubscriptions(res.data ?? [])
  }, [])

  const openDetail = useCallback(async (a: AssetSummary) => {
    const res = await fetchAsset(a.id)
    if (res.code !== 0 || !res.data) {
      setToast(res.message ?? t("loadFailed"))
      return
    }
    setSelected(res.data)
    setLineage(null)
    setQuality(null)
    // 懒加载血缘 + 质量（降级安全）
    void fetchAssetLineage(a.id).then((r) => setLineage(r.data ?? null))
    void fetchAssetQuality(a.id).then((r) => setQuality(r.data ?? null))
    void loadSubscriptions()
  }, [t, loadSubscriptions])

  const openCreate = useCallback(async () => {
    setCreateOpen(true)
    if (datasources.length === 0) {
      try {
        const ds = await listDatasources()
        setDatasources(ds.map((d) => ({ id: d.id, name: d.name })))
      } catch {
        /* 数据源拉取失败不阻断弹窗;Select 空态 */
      }
    }
  }, [datasources.length])

  const submitCreate = useCallback(async (payload: Record<string, unknown>) => {
    const r = resolveGate(await createAsset(payload))
    setToast(gateToast(r, t, ASSET_ERR_KEYS))
    if (r.kind !== "failed") {
      void runSearch()
      return true
    }
    return false
  }, [t, runSearch])

  const submitEdit = useCallback(async (payload: Record<string, unknown>) => {
    if (!selected) return false
    if (Object.keys(payload).length === 0) {
      setToast(t("noChanges"))
      return true
    }
    const r = resolveGate(await updateAsset(selected.id, payload))
    setToast(gateToast(r, t, ASSET_ERR_KEYS))
    if (r.kind !== "failed") {
      void runSearch()
      void refreshDetail(selected.id)
      return true
    }
    return false
  }, [selected, t, runSearch, refreshDetail])

  const doConfirm = useCallback(async () => {
    if (!selected || !confirm) return
    setConfirmBusy(true)
    const res = confirm === "retire" ? await retireAsset(selected.id) : await reconcileAsset(selected.id)
    const r = resolveGate(res)
    setToast(gateToast(r, t, ASSET_ERR_KEYS))
    setConfirmBusy(false)
    setConfirm(null)
    if (r.kind !== "failed") {
      void runSearch()
      void refreshDetail(selected.id)
    }
  }, [selected, confirm, t, runSearch, refreshDetail])

  const doSubscribe = useCallback(async () => {
    if (!selected) return
    const r = resolveGate(await subscribe({ targetType: "ASSET", targetId: selected.id, changeFilter: "schema,quality,freshness" }))
    setToast(r.kind === "executed" ? t("subscribed") : gateToast(r, t, ASSET_ERR_KEYS))
    if (r.kind !== "failed") void loadSubscriptions()
  }, [selected, t, loadSubscriptions])

  /** 退订（dialog 与详情内联共用）。返回是否成功（已执行/待审批）。 */
  const doUnsubscribe = useCallback(async (subId: number) => {
    const r = resolveGate(await unsubscribe(subId))
    setToast(r.kind === "executed" ? t("unsubscribed") : gateToast(r, t, ASSET_ERR_KEYS))
    if (r.kind !== "failed") {
      void loadSubscriptions()
      return true
    }
    return false
  }, [t, loadSubscriptions])

  const currentSub = selected ? findAssetSubscription(subscriptions, selected.id) : null

  const facets = result?.facets ?? {}

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
                onClick={() => setSensitivity(k)}
                className={`flex justify-between rounded px-2 py-1 text-left text-sm ${sensitivity === k ? "bg-accent" : "hover:bg-accent/50"}`}
              >
                <span className={SENSITIVITY_TONE[k] ?? ""}>{k}</span>
                <span className="text-muted-foreground">{c}</span>
              </button>
            ))}
          </div>
        </div>
        {Object.keys(facets.owner ?? {}).length > 0 && (
          <div>
            <div className="mb-2 text-xs font-medium text-muted-foreground">{t("facetOwner")}</div>
            <div className="flex flex-col gap-1">
              {Object.entries(facets.owner ?? {}).map(([k, c]) => (
                <div key={k} className="flex justify-between px-2 py-1 text-sm text-muted-foreground">
                  <span>#{k}</span>
                  <span>{c}</span>
                </div>
              ))}
            </div>
          </div>
        )}
      </aside>

      {/* 中：列表 */}
      <section className="flex min-w-0 flex-1 flex-col">
        <div className="flex items-center justify-between border-b border-border px-4 py-2 text-sm">
          <span className="font-medium">{t("title")}</span>
          <div className="flex items-center gap-3">
            <span className="text-muted-foreground">
              {loading ? t("loading") : t("totalCount", { count: result?.total ?? 0 })}
              {result?.truncated ? ` · ${t("truncated")}` : ""}
            </span>
            <Button size="sm" variant="outline" onClick={() => setSubsOpen(true)}>
              <HugeiconsIcon icon={Notification01Icon} className="size-4" />
              {t("mySubscriptions")}
            </Button>
            <Button size="sm" onClick={openCreate}>
              <HugeiconsIcon icon={Add01Icon} className="size-4" />
              {t("createAction")}
            </Button>
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
      </section>

      {/* 右：详情 */}
      <aside className="flex w-80 shrink-0 flex-col border-l border-border">
        {selected ? (
          <div className="flex min-h-0 flex-1 flex-col overflow-auto p-4">
            <div className="mb-1 flex items-start justify-between gap-2">
              <div className="min-w-0">
                <div className="text-base font-semibold">{selected.name || selected.qualifiedName}</div>
                <div className="text-xs text-muted-foreground">{selected.qualifiedName}</div>
              </div>
              <Button size="icon-sm" variant="ghost" onClick={() => setEditOpen(true)} aria-label={t("editAction")}>
                <HugeiconsIcon icon={Edit02Icon} className="size-4" />
              </Button>
            </div>

            {selected.status !== "ACTIVE" && (
              <div className="mb-3 mt-2 rounded border border-amber-300 bg-amber-50 px-2 py-1 text-xs text-amber-700">
                {t("staleHint")}
              </div>
            )}

            <dl className="mt-2 space-y-2 text-sm">
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

            {/* 血缘入口（降级隐藏） */}
            {lineage && lineage.available && !lineage.degraded && (
              <div className="mt-4 flex items-center gap-2 rounded-md border border-border px-3 py-2 text-sm">
                <HugeiconsIcon icon={GitBranchIcon} className="size-4 text-muted-foreground" />
                <span>{t("lineage")}</span>
                <span className="ml-auto text-xs text-muted-foreground">
                  ↑{lineage.upstreamCount} ↓{lineage.downstreamCount}
                </span>
              </div>
            )}

            {/* 质量徽章（降级隐藏） */}
            {quality && quality.available && !quality.degraded && quality.score != null && (
              <div className="mt-2 flex items-center gap-2 rounded-md border border-border px-3 py-2 text-sm">
                <HugeiconsIcon icon={Shield01Icon} className="size-4 text-emerald-600" />
                <span>{t("quality")}</span>
                <span className="ml-auto text-xs">{quality.grade ?? quality.score}</span>
              </div>
            )}

            {/* 写操作 */}
            <div className="mt-4 flex flex-col gap-2">
              {currentSub ? (
                <Button variant="outline" onClick={() => void doUnsubscribe(currentSub.id)}>
                  <HugeiconsIcon icon={Notification01Icon} className="size-4" />
                  {t("unsubscribeAction")}
                </Button>
              ) : (
                <Button variant="default" onClick={doSubscribe}>
                  <HugeiconsIcon icon={Notification01Icon} className="size-4" />
                  {t("subscribe")}
                </Button>
              )}
              <div className="flex gap-2">
                <Button variant="outline" className="flex-1" onClick={() => setConfirm("reconcile")}>
                  <HugeiconsIcon icon={RefreshIcon} className="size-4" />
                  {t("reconcileAction")}
                </Button>
                {selected.status !== "RETIRED" && (
                  <Button variant="outline" className="flex-1" onClick={() => setConfirm("retire")}>
                    <HugeiconsIcon icon={Delete02Icon} className="size-4" />
                    {t("retireAction")}
                  </Button>
                )}
              </div>
            </div>
          </div>
        ) : (
          <div className="flex flex-1 items-center justify-center p-8 text-center text-sm text-muted-foreground">
            {t("selectHint")}
          </div>
        )}
      </aside>

      {/* 创建 */}
      <AssetFormDialog
        open={createOpen}
        onOpenChange={setCreateOpen}
        mode="create"
        datasources={datasources}
        onSubmit={submitCreate}
      />
      {/* 编辑 */}
      <AssetFormDialog
        open={editOpen}
        onOpenChange={setEditOpen}
        mode="edit"
        initial={selected}
        datasources={datasources}
        onSubmit={submitEdit}
      />
      {/* 我的订阅 */}
      <SubscriptionsDialog open={subsOpen} onOpenChange={setSubsOpen} onUnsubscribe={doUnsubscribe} />
      {/* 下线 / 对账 确认 */}
      <ConfirmDialog
        open={confirm !== null}
        onOpenChange={(o) => !o && setConfirm(null)}
        title={confirm === "retire" ? t("retireConfirmTitle") : t("reconcileConfirmTitle")}
        description={confirm === "retire" ? t("retireConfirmDesc") : t("reconcileConfirmDesc")}
        confirmLabel={confirm === "retire" ? t("retireAction") : t("reconcileAction")}
        destructive={confirm === "retire"}
        busy={confirmBusy}
        onConfirm={doConfirm}
      />

      {toast && (
        <div
          className="fixed bottom-4 right-4 z-50 rounded-md bg-foreground px-3 py-2 text-sm text-background shadow-lg"
          role="status"
        >
          {toast}
        </div>
      )}
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
