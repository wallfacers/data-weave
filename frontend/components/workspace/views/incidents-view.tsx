"use client"

/**
 * 监督席 —— 故障工单队列，异常自动开单、自动愈合（064 重构）。
 *
 * 使用标准 Tabs 组件（下划线式）切换"信号流"和"工单队列"两个子面板。
 * 所有 UI 原语走项目公共组件目录：Tabs/DwScroll/Card/Badge/LoadingState/DropdownSelect。
 *
 * 设计约束（DESIGN.md）：语义 token、无分割线、hugeicons、不手写 dark:。
 */

import { useCallback, useState } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import { BugIcon, Alert02Icon, ArrowRight01Icon } from "@hugeicons/core-free-icons"
import { toast } from "sonner"

import type { ViewProps } from "@/lib/workspace/registry"
import { useProjectContext } from "@/lib/project-context"
import { useWorkspaceStore } from "@/lib/workspace/store"
import { useLiveData } from "@/lib/workspace/use-api"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { DwScroll } from "@/components/ui/dw-scroll"
import { Card, CardContent } from "@/components/ui/card"
import { LoadingState } from "@/components/workspace/shared/loading-state"
import { ViewRefreshControl } from "@/components/workspace/views/view-refresh-control"
import {
  fetchIncidentQueue,
  fetchIncidentHistory,
  fetchIncidentDetail,
  rerunIncident,
  type IncidentCard,
  type IncidentQueue,
  type IncidentDetail,
} from "@/lib/incident-api"
import {
  SeverityBadge,
  StateBadge,
  BlastRadiusBadge,
  SlaCountdown,
  PriorCountBadge,
  DiagnosisPlaceholder,
  ProposalPlaceholder,
} from "./incident/triage"
import {
  RerunButton,
  SuppressDialog,
  NoteInput,
  PendingApprovalInline,
  IncidentDeepLink,
} from "./incident/actions"
import { SignalStreamPanel } from "./incident/signal-stream-panel"
import { IncidentTimelineDialog } from "@/components/workspace/incident-timeline-dialog"

type TabKey = "signals" | "tickets"

export function IncidentsView({ active }: ViewProps) {
  const t = useTranslations("incidents")
  const tc = useTranslations("common")

  const projectId = useProjectContext((s) => s.currentProjectId)
  const openViewFn = useWorkspaceStore((s) => s.open)
  const [tab, setTab] = useState<TabKey>("signals")
  const [autoEnabled, setAutoEnabled] = useState(true)

  // ── 工单数据（15s 轮询）──────────────────────────────────
  const {
    data: queue,
    loading,
    refreshing,
    stale,
    lastUpdatedAt,
    refresh,
  } = useLiveData<IncidentQueue>(
    () => {
      if (projectId == null)
        return Promise.resolve({ active: [], recentResolved: [], activeCount: 0, recentResolvedCount: 0 })
      return fetchIncidentQueue(String(projectId))
    },
    { active, enabled: autoEnabled, intervalMs: 15_000, deps: [projectId] },
  )

  // ── 历史面板 ────────────────────────────────────────────
  const [showHistory, setShowHistory] = useState(false)
  const [historyItems, setHistoryItems] = useState<IncidentCard[]>([])
  const [historyTotal, setHistoryTotal] = useState(0)
  const [historyLoading, setHistoryLoading] = useState(false)

  const loadHistory = useCallback(async () => {
    if (projectId == null) return
    setShowHistory(true)
    setHistoryLoading(true)
    try {
      const r = await fetchIncidentHistory(String(projectId), { page: 0, size: 20 })
      setHistoryItems(r.items)
      setHistoryTotal(r.total)
    } catch {
      toast.error(t("loadError"))
    } finally {
      setHistoryLoading(false)
    }
  }, [projectId, t])

  // ── 时间线抽屉 ──────────────────────────────────────────
  const [detailId, setDetailId] = useState<number | null>(null)
  const [detail, setDetail] = useState<IncidentDetail | null>(null)
  const [detailLoading, setDetailLoading] = useState(false)

  const openTimeline = useCallback(async (id: number) => {
    setDetailId(id)
    setDetailLoading(true)
    try {
      if (projectId != null) {
        const d = await fetchIncidentDetail(id, String(projectId))
        setDetail(d)
      }
    } catch {
      toast.error(t("loadError"))
    } finally {
      setDetailLoading(false)
    }
  }, [projectId, t])

  // ── 静默 / 备注弹窗 ─────────────────────────────────────
  const [suppressTarget, setSuppressTarget] = useState<IncidentCard | null>(null)
  const [noteTarget, setNoteTarget] = useState<IncidentCard | null>(null)

  const activeCards = queue?.active ?? []
  const resolvedCards = queue?.recentResolved ?? []

  // ── 空态（整体无数据）────────────────────────────────────
  if (!loading && activeCards.length === 0 && resolvedCards.length === 0) {
    return (
      <div className="flex min-h-0 min-w-0 flex-1 flex-col p-(--card-spacing)">
        <div className="shrink-0 flex items-center justify-between pb-3">
          <div>
            <h2 className="text-sm font-medium">{t("title")}</h2>
            <p className="text-xs text-muted-foreground">{t("subtitle")}</p>
          </div>
          <ViewRefreshControl
            lastUpdatedAt={lastUpdatedAt}
            refreshing={refreshing}
            stale={stale}
            autoEnabled={autoEnabled}
            onToggleAuto={setAutoEnabled}
            onRefresh={refresh}
          />
        </div>
        <DwScroll className="flex-1" innerClassName="min-h-full flex flex-col items-center justify-center py-16 text-center">
          <HugeiconsIcon icon={BugIcon} className="size-12 text-muted-foreground/30" />
          <p className="mt-4 text-sm font-medium text-muted-foreground">{t("emptyActive")}</p>
          <p className="mt-1 text-xs text-muted-foreground/70">{t("emptyActiveHint")}</p>
        </DwScroll>
      </div>
    )
  }

  // ── 正常渲染 ────────────────────────────────────────────
  return (
    <div className="flex min-h-0 min-w-0 flex-1 flex-col">
      <Tabs value={tab} onValueChange={(v) => setTab(v as TabKey)}>
        {/* Tab bar */}
        <div className="shrink-0 flex items-center justify-between px-(--card-spacing)">
          <TabsList size="md">
            <TabsTrigger value="signals" icon={Alert02Icon}>
              {t("tab.signals") || "信号流"}
            </TabsTrigger>
            <TabsTrigger value="tickets" icon={BugIcon}>
              {t("tab.tickets") || "工单队列"}
            </TabsTrigger>
          </TabsList>
          {tab === "tickets" && (
            <div className="flex items-center gap-2 pr-2">
              <button
                onClick={loadHistory}
                className="text-xs text-muted-foreground hover:text-foreground transition-colors flex items-center gap-1"
              >
                {t("history")}
                <HugeiconsIcon icon={ArrowRight01Icon} className="size-3" />
              </button>
              <ViewRefreshControl
                lastUpdatedAt={lastUpdatedAt}
                refreshing={refreshing}
                stale={stale}
                autoEnabled={autoEnabled}
                onToggleAuto={setAutoEnabled}
                onRefresh={refresh}
              />
            </div>
          )}
        </div>

        {/* 信号流 Tab */}
        <TabsContent value="signals" className="flex min-h-0 flex-1 flex-col">
          <SignalStreamPanel active={tab === "signals"} />
        </TabsContent>

        {/* 工单队列 Tab */}
        <TabsContent value="tickets" className="flex min-h-0 flex-1 flex-col">
          {loading ? (
            <LoadingState />
          ) : (
            <DwScroll className="flex-1" innerClassName="flex flex-col gap-6 p-(--card-spacing)">
              {/* 活跃区 */}
              <section>
                <div className="flex items-center gap-2 mb-3">
                  <HugeiconsIcon icon={Alert02Icon} className="size-4 text-destructive" />
                  <h3 className="text-sm font-medium">
                    {t("activeSection", { count: queue?.activeCount ?? 0 })}
                  </h3>
                </div>
                {activeCards.length === 0 ? (
                  <p className="text-xs text-muted-foreground py-4 text-center">{t("emptyActiveHint")}</p>
                ) : (
                  <div className="flex flex-col gap-2">
                    {activeCards.map((card) => (
                      <IncidentCardItem
                        key={card.id}
                        card={card}
                        projectId={projectId}
                        onRefresh={refresh}
                        onOpenTimeline={openTimeline}
                        onSuppress={setSuppressTarget}
                        onNote={setNoteTarget}
                        detailId={detailId}
                        setDetailId={setDetailId}
                        detail={detail}
                        detailLoading={detailLoading}
                        suppressTarget={suppressTarget}
                        setSuppressTarget={setSuppressTarget}
                        noteTarget={noteTarget}
                        setNoteTarget={setNoteTarget}
                        openView={openViewFn}
                      />
                    ))}
                  </div>
                )}
              </section>

              {/* 近 24h 已解决区（降权） */}
              {resolvedCards.length > 0 && (
                <section className="opacity-60">
                  <h3 className="text-xs font-medium text-muted-foreground mb-3">
                    {t("recentResolvedSection", { count: queue?.recentResolvedCount ?? 0 })}
                  </h3>
                  <div className="flex flex-col gap-2">
                    {resolvedCards.map((card) => (
                      <IncidentCardItem
                        key={card.id}
                        card={card}
                        projectId={projectId}
                        onRefresh={refresh}
                        onOpenTimeline={openTimeline}
                        onSuppress={setSuppressTarget}
                        onNote={setNoteTarget}
                        detailId={detailId}
                        setDetailId={setDetailId}
                        detail={detail}
                        detailLoading={detailLoading}
                        suppressTarget={suppressTarget}
                        setSuppressTarget={setSuppressTarget}
                        noteTarget={noteTarget}
                        setNoteTarget={setNoteTarget}
                        openView={openViewFn}
                        dimmed
                      />
                    ))}
                  </div>
                </section>
              )}
            </DwScroll>
          )}

          {/* 历史面板（内部滚动） */}
          {showHistory && (
            <IncidentHistoryPanel
              items={historyItems}
              total={historyTotal}
              loading={historyLoading}
              onClose={() => setShowHistory(false)}
              t={t}
            />
          )}
        </TabsContent>
      </Tabs>
    </div>
  )
}

// ═══════════════════════════════════════════════════════════
// 单张工单卡片
// ═══════════════════════════════════════════════════════════

function IncidentCardItem({
  card,
  projectId,
  onRefresh,
  onOpenTimeline,
  onSuppress,
  onNote,
  detailId,
  setDetailId,
  detail,
  detailLoading,
  suppressTarget,
  setSuppressTarget,
  noteTarget,
  setNoteTarget,
  openView,
  dimmed,
}: {
  card: IncidentCard
  projectId: number | null
  onRefresh: () => Promise<void>
  onOpenTimeline: (id: number) => void
  onSuppress: (card: IncidentCard) => void
  onNote: (card: IncidentCard) => void
  detailId: number | null
  setDetailId: (id: number | null) => void
  detail: IncidentDetail | null
  detailLoading: boolean
  suppressTarget: IncidentCard | null
  setSuppressTarget: (card: IncidentCard | null) => void
  noteTarget: IncidentCard | null
  setNoteTarget: (card: IncidentCard | null) => void
  openView: (view: string, params?: Record<string, unknown>) => void
  dimmed?: boolean
}) {
  const t = useTranslations("incidents")
  const tc = useTranslations("common")
  const [rerunning, setRerunning] = useState(false)
  const isActive = card.state === "OPEN" || card.state === "MITIGATING"

  const handleRerun = useCallback(async () => {
    if (projectId == null) return
    setRerunning(true)
    try {
      const result = await rerunIncident(card.id, card.sourceRefId, String(projectId))
      if (result.outcome === "EXECUTED") {
        toast.success(t("rerun.executed"))
      } else if (result.outcome === "PENDING_APPROVAL") {
        toast(t("rerun.pendingApproval"))
      } else {
        toast.error(t("rerun.rejected", { message: result.message }))
      }
      await onRefresh()
    } catch (e) {
      toast.error(e instanceof Error ? e.message : tc("operationFailed"))
    } finally {
      setRerunning(false)
    }
  }, [card.id, card.sourceRefId, projectId, onRefresh, t, tc])

  return (
    <Card size="sm">
      <CardContent>
        {/* 顶行：严重度 + 标题 + 状态 */}
        <div className="flex items-center gap-2">
          <SeverityBadge severity={card.severity} t={t} />
          <span className="text-sm font-medium flex-1 min-w-0 truncate">{card.title}</span>
          {card.occurrenceCount > 1 && (
            <span className="text-xs text-muted-foreground shrink-0">
              {t("card.occurrenceCount", { count: card.occurrenceCount })}
            </span>
          )}
          <StateBadge state={card.state} t={t} />
        </div>

        {/* 分诊行 */}
        {isActive && (
          <div className="flex items-center gap-3 text-xs text-muted-foreground mt-2">
            <BlastRadiusBadge blastRadius={card.blastRadius} t={t} />
            <SlaCountdown timeBudgetAt={card.timeBudgetAt} t={t} />
            <PriorCountBadge count={card.priorIncidentCount} t={t} />
          </div>
        )}

        {/* 诊断/提案占位 */}
        {isActive && (
          <div className="flex items-center gap-3 text-xs mt-2">
            <DiagnosisPlaceholder t={t} />
            <ProposalPlaceholder t={t} />
          </div>
        )}

        {/* 已解决行 */}
        {!isActive && card.resolvedAt && (
          <p className="text-xs text-muted-foreground mt-2">
            {card.resolutionKind === "AUTO_HEAL"
              ? t("card.autoHealed")
              : t("card.resolvedAt", { time: card.resolvedAt })}
          </p>
        )}

        {/* 静默原因 */}
        {card.suppressReason && (
          <p className="text-xs text-muted-foreground mt-1">
            {t("card.suppressReason", { reason: card.suppressReason })}
          </p>
        )}

        {/* 动作区 */}
        {isActive && !dimmed && (
          <div className="flex items-center gap-2 pt-2 mt-2 border-t border-border/50">
            <RerunButton onClick={handleRerun} loading={rerunning} t={t} />
            <button
              onClick={() => onSuppress(card)}
              className="text-xs text-muted-foreground hover:text-foreground transition-colors"
            >
              {t("action.suppress")}
            </button>
            <button
              onClick={() => onOpenTimeline(card.id)}
              className="text-xs text-muted-foreground hover:text-foreground transition-colors"
            >
              {t("action.viewTimeline")}
            </button>
            <button
              onClick={() => onNote(card)}
              className="text-xs text-muted-foreground hover:text-foreground transition-colors"
            >
              {t("action.addNote")}
            </button>
            {card.pendingActionCount > 0 && (
              <PendingApprovalInline
                incidentId={card.id}
                projectId={projectId}
                onRefresh={onRefresh}
                t={t}
              />
            )}
            <IncidentDeepLink card={card} open={openView} t={t} />
          </div>
        )}

        {/* 弹窗/抽屉 */}
        <SuppressDialog
          target={card}
          open={suppressTarget?.id === card.id}
          onOpenChange={(v) => { if (!v) setSuppressTarget(null) }}
          projectId={projectId}
          onRefresh={onRefresh}
          t={t}
          tc={tc}
        />
        <NoteInput
          target={card}
          open={noteTarget?.id === card.id}
          onOpenChange={(v) => { if (!v) setNoteTarget(null) }}
          projectId={projectId}
          onRefresh={onRefresh}
          t={t}
          tc={tc}
        />
        <IncidentTimelineDialog
          incidentId={card.id}
          open={detailId === card.id}
          onOpenChange={(v) => { if (!v) setDetailId(null) }}
          detail={detail}
          loading={detailLoading}
          t={t}
        />
      </CardContent>
    </Card>
  )
}

// ═══════════════════════════════════════════════════════════
// 历史面板
// ═══════════════════════════════════════════════════════════

function IncidentHistoryPanel({
  items,
  total,
  loading,
  onClose,
  t,
}: {
  items: IncidentCard[]
  total: number
  loading: boolean
  onClose: () => void
  t: ReturnType<typeof useTranslations<"incidents">>
}) {
  return (
    <div className="shrink-0 border-t">
      <div className="flex items-center justify-between px-(--card-spacing) py-3">
        <h3 className="text-sm font-medium">{t("history")} ({total})</h3>
        <button
          onClick={onClose}
          className="text-xs text-muted-foreground hover:text-foreground transition-colors"
        >
          {t("close") || "关闭"}
        </button>
      </div>
      {loading ? (
        <LoadingState />
      ) : items.length === 0 ? (
        <p className="text-xs text-muted-foreground text-center py-8">{t("emptyHistory")}</p>
      ) : (
        <DwScroll className="max-h-64" innerClassName="flex flex-col gap-2 px-(--card-spacing) pb-3">
          {items.map((card) => (
            <div key={card.id} className="flex items-center gap-2 text-xs">
              <SeverityBadge severity={card.severity} t={t} />
              <span className="flex-1 truncate">{card.title}</span>
              <StateBadge state={card.state} t={t} />
            </div>
          ))}
        </DwScroll>
      )}
    </div>
  )
}
