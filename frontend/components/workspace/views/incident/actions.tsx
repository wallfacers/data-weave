"use client"

import { useEffect, useState, useCallback } from "react"
import { toast } from "sonner"

import { Dialog } from "@/components/ui/dialog"
import {
  fetchIncidentDetail,
  rerunIncident,
  suppressIncident,
  unsuppressIncident,
  addIncidentNote,
  approveAction,
  rejectAction,
  type IncidentCard,
  type IncidentDetail,
  type IncidentAction,
} from "@/lib/incident-api"
import { refKindToView } from "@/lib/event-center-api"

// —— 重跑按钮（按 outcome 分流）——
export function RerunButton({
  onClick,
  loading,
  t,
}: {
  onClick: () => void
  loading: boolean
  t: ReturnType<typeof import("next-intl").useTranslations<"incidents">>
}) {
  return (
    <button
      onClick={onClick}
      disabled={loading}
      className="inline-flex items-center gap-1 rounded bg-primary px-2.5 py-1 text-xs font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50 transition-colors"
    >
      {loading ? (
        <svg className="size-3 animate-spin" viewBox="0 0 24 24" fill="none">
          <circle cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" className="opacity-25" />
          <path d="M4 12a8 8 0 018-8v4a4 4 0 00-4 4H4z" fill="currentColor" className="opacity-75" />
        </svg>
      ) : null}
      {t("action.rerun")}
    </button>
  )
}

// —— 静默弹窗（原因必填）——
export function SuppressDialog({
  target,
  open,
  onOpenChange,
  projectId,
  onRefresh,
  t,
  tc,
}: {
  target: IncidentCard | null
  open: boolean
  onOpenChange: (open: boolean) => void
  projectId: number | null
  onRefresh: () => Promise<void>
  t: ReturnType<typeof import("next-intl").useTranslations<"incidents">>
  tc: ReturnType<typeof import("next-intl").useTranslations<"common">>
}) {
  const [reason, setReason] = useState("")
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState("")

  const handleSuppress = useCallback(async () => {
    if (!reason.trim()) {
      setError(t("suppressDialog.reasonRequired"))
      return
    }
    if (!target || projectId == null) return
    setSubmitting(true)
    setError("")
    try {
      await suppressIncident(target.id, reason.trim(), String(projectId))
      toast.success(tc("operationSuccess"))
      onOpenChange(false)
      setReason("")
      await onRefresh()
    } catch (e) {
      toast.error(e instanceof Error ? e.message : tc("operationFailed"))
    } finally {
      setSubmitting(false)
    }
  }, [target, reason, projectId, onRefresh, onOpenChange, t, tc])

  if (!target) return null

  return (
    <Dialog
      open={open}
      onOpenChange={(v) => {
        if (!v) { setReason(""); setError("") }
        onOpenChange(v)
      }}
    >
      <div className="flex flex-col gap-4 p-4">
        <h3 className="text-sm font-medium">{t("suppressDialog.title")}</h3>
        <p className="text-xs text-muted-foreground">{t("suppressDialog.description")}</p>
        <label className="flex flex-col gap-1.5">
          <span className="text-xs font-medium">{t("suppressDialog.reasonLabel")}</span>
          <input
            type="text"
            value={reason}
            onChange={(e) => { setReason(e.target.value); setError("") }}
            placeholder={t("suppressDialog.reasonPlaceholder")}
            className="rounded-md border bg-transparent px-3 py-2 text-sm outline-none focus:ring-1 focus:ring-ring"
            autoFocus
          />
          {error && <span className="text-xs text-destructive">{error}</span>}
        </label>
        <div className="flex items-center justify-end gap-2">
          <button
            onClick={() => onOpenChange(false)}
            className="rounded px-3 py-1.5 text-xs text-muted-foreground hover:text-foreground transition-colors"
          >
            {t("suppressDialog.cancel")}
          </button>
          <button
            onClick={handleSuppress}
            disabled={submitting}
            className="rounded bg-primary px-3 py-1.5 text-xs font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
          >
            {submitting ? tc("loading") : t("suppressDialog.confirm")}
          </button>
        </div>
      </div>
    </Dialog>
  )
}

// —— 待审批内联（调既有 /api/approvals/{id}/approve|reject）——
export function PendingApprovalInline({
  incidentId,
  projectId,
  onRefresh,
  t,
}: {
  incidentId: number
  projectId: number | null
  onRefresh: () => Promise<void>
  t: ReturnType<typeof import("next-intl").useTranslations<"incidents">>
}) {
  const [actions, setActions] = useState<IncidentAction[]>([])
  const [loaded, setLoaded] = useState(false)
  const [approving, setApproving] = useState<number | null>(null)
  const [rejecting, setRejecting] = useState<number | null>(null)

  useEffect(() => {
    if (projectId == null) return
    let alive = true
    fetchIncidentDetail(incidentId, String(projectId))
      .then((d) => {
        if (!alive) return
        setActions(d.actions?.filter((a) => a.approvalStatus === "PENDING") ?? [])
        setLoaded(true)
      })
      .catch(() => { if (alive) setLoaded(true) })
    return () => { alive = false }
  }, [incidentId, projectId])

  const handleApprove = useCallback(async (actionId: number) => {
    setApproving(actionId)
    try {
      await approveAction(actionId)
      toast.success(t("action.approve") + " OK")
      setActions((prev) => prev.filter((a) => a.id !== actionId))
      await onRefresh()
    } catch (e) {
      toast.error(e instanceof Error ? e.message : t("action.approve") + " failed")
    } finally {
      setApproving(null)
    }
  }, [onRefresh, t])

  const handleReject = useCallback(async (actionId: number) => {
    setRejecting(actionId)
    try {
      await rejectAction(actionId)
      toast(t("action.reject") + " OK")
      setActions((prev) => prev.filter((a) => a.id !== actionId))
      await onRefresh()
    } catch (e) {
      toast.error(e instanceof Error ? e.message : t("action.reject") + " failed")
    } finally {
      setRejecting(null)
    }
  }, [onRefresh, t])

  if (!loaded || actions.length === 0) return null

  return (
    <div className="flex items-center gap-1.5">
      {actions.map((a) => (
        <span key={a.id} className="inline-flex items-center gap-1 text-xs">
          <span className="text-warning">{t("pendingApproval")}</span>
          <button
            onClick={() => handleApprove(a.id)}
            disabled={approving === a.id || rejecting != null}
            className="text-xs text-success hover:underline disabled:opacity-50"
          >
            {approving === a.id ? t("loading") : t("action.approve")}
          </button>
          <button
            onClick={() => handleReject(a.id)}
            disabled={rejecting === a.id || approving != null}
            className="text-xs text-destructive hover:underline disabled:opacity-50"
          >
            {rejecting === a.id ? t("loading") : t("action.reject")}
          </button>
        </span>
      ))}
    </div>
  )
}

// —— 时间线抽屉 ——
export function TimelineDrawer({
  incidentId,
  open,
  onOpenChange,
  detail,
  loading,
  t,
  openView,
}: {
  incidentId: number
  open: boolean
  onOpenChange: (open: boolean) => void
  detail: IncidentDetail | null
  loading: boolean
  t: ReturnType<typeof import("next-intl").useTranslations<"incidents">>
  openView: (view: string, params?: Record<string, unknown>) => void
}) {
  if (!open) return null

  const kindIcons: Record<string, string> = {
    SIGNAL: "⚡",
    STATE_CHANGE: "🔄",
    ACTION: "▶",
    APPROVAL: "✓",
    NOTE: "📝",
  }

  return (
    <div className="fixed inset-y-0 right-0 z-50 w-80 border-l bg-background shadow-lg flex flex-col">
      <div className="flex items-center justify-between p-4 border-b border-border/50">
        <h3 className="text-sm font-medium">{t("timeline.title")}</h3>
        <button
          onClick={() => onOpenChange(false)}
          className="text-muted-foreground hover:text-foreground transition-colors"
        >
          <svg className="size-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <line x1="18" y1="6" x2="6" y2="18" />
            <line x1="6" y1="6" x2="18" y2="18" />
          </svg>
        </button>
      </div>
      <div className="flex-1 overflow-y-auto p-4">
        {loading ? (
          <p className="text-xs text-muted-foreground">{t("loading")}</p>
        ) : !detail?.timeline?.length ? (
          <p className="text-xs text-muted-foreground text-center py-8">{t("timeline.empty")}</p>
        ) : (
          <div className="flex flex-col gap-3">
            {detail.timeline.map((entry) => (
              <div key={entry.seq} className="flex gap-2 text-xs">
                <span className="mt-0.5 shrink-0">{kindIcons[entry.kind] ?? "·"}</span>
                <div className="flex flex-col gap-0.5 min-w-0">
                  <span className="text-muted-foreground">
                    {t(`timeline.kind.${entry.kind}` as never)} · {entry.actor === "system" ? t("timeline.actorSystem") : entry.actor}
                  </span>
                  {entry.payload && Object.keys(entry.payload).length > 0 && (
                    <span className="text-muted-foreground/70 truncate">
                      {JSON.stringify(entry.payload)}
                    </span>
                  )}
                  <span className="text-muted-foreground/50">{entry.createdAt}</span>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}

// —— 备注输入 ——
export function NoteInput({
  target,
  open,
  onOpenChange,
  projectId,
  onRefresh,
  t,
  tc,
}: {
  target: IncidentCard | null
  open: boolean
  onOpenChange: (open: boolean) => void
  projectId: number | null
  onRefresh: () => Promise<void>
  t: ReturnType<typeof import("next-intl").useTranslations<"incidents">>
  tc: ReturnType<typeof import("next-intl").useTranslations<"common">>
}) {
  const [text, setText] = useState("")
  const [submitting, setSubmitting] = useState(false)

  const handleSubmit = useCallback(async () => {
    if (!target || projectId == null || !text.trim()) return
    setSubmitting(true)
    try {
      await addIncidentNote(target.id, text.trim(), String(projectId))
      toast.success(tc("operationSuccess"))
      onOpenChange(false)
      setText("")
      await onRefresh()
    } catch (e) {
      toast.error(e instanceof Error ? e.message : tc("operationFailed"))
    } finally {
      setSubmitting(false)
    }
  }, [target, text, projectId, onRefresh, onOpenChange, t, tc])

  if (!target) return null

  return (
    <Dialog
      open={open}
      onOpenChange={(v) => {
        if (!v) setText("")
        onOpenChange(v)
      }}
    >
      <div className="flex flex-col gap-4 p-4">
        <h3 className="text-sm font-medium">{t("noteDialog.title")}</h3>
        <textarea
          value={text}
          onChange={(e) => setText(e.target.value)}
          placeholder={t("noteDialog.placeholder")}
          rows={4}
          maxLength={2000}
          className="rounded-md border bg-transparent px-3 py-2 text-sm outline-none focus:ring-1 focus:ring-ring resize-none"
        />
        <div className="flex items-center justify-end gap-2">
          <button
            onClick={() => onOpenChange(false)}
            className="rounded px-3 py-1.5 text-xs text-muted-foreground hover:text-foreground transition-colors"
          >
            {t("noteDialog.cancel")}
          </button>
          <button
            onClick={handleSubmit}
            disabled={submitting || !text.trim()}
            className="rounded bg-primary px-3 py-1.5 text-xs font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
          >
            {t("noteDialog.confirm")}
          </button>
        </div>
      </div>
    </Dialog>
  )
}

// —— 深链（复用 event-center 的 refKindToView）——
export function IncidentDeepLink({
  card,
  open,
  t,
}: {
  card: IncidentCard
  open: (view: string, params?: Record<string, unknown>) => void
  t: ReturnType<typeof import("next-intl").useTranslations<"incidents">>
}) {
  const view = refKindToView(card.sourceKind as never)
  if (!view) return null

  const kindLabel = card.sourceKind === "TASK" ? t("action.viewInstance") : t("action.viewWorkflow")

  return (
    <button
      onClick={() => open(view, { ref: card.sourceRefId })}
      className="text-xs text-link hover:underline ml-auto"
    >
      {kindLabel}
    </button>
  )
}
