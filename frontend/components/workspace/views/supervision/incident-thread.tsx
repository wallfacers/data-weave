"use client"

/**
 * T037 下钻线程：六类消息形态 + 证据下钻链到实例日志 + 人工协同按钮（mark-handled/reverify/close/
 * 批准/驳回）+ 对话输入。Agent 回复的 delta 打字流实时附在消息流末尾。
 */
import { useMemo, useState } from "react"
import { useTranslations } from "next-intl"
import { toast } from "sonner"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  Cancel01Icon,
  CheckmarkBadge01Icon,
  DocumentCodeIcon,
  PlayIcon,
  SecurityCheckIcon,
} from "@hugeicons/core-free-icons"

import { Button } from "@/components/ui/button"
import { DwScroll } from "@/components/ui/dw-scroll"
import { cn } from "@/lib/utils"
import { useFormatDateTime } from "@/hooks/use-format-date-time"
import * as api from "@/lib/supervision/api"
import type {
  ConnectionPhase,
  Incident,
  IncidentLiveState,
  IncidentMessage,
  IncidentProposal,
} from "@/lib/supervision/types"
import { isTerminal } from "@/lib/supervision/types"
import { ChatMarkdown } from "@/components/workspace/shared/chat-markdown"
import { ClassificationBadge, StateBadge, ThinkingDots, ToolChip } from "./incident-visuals"
import { ChatComposer } from "./chat-composer"

function currentUser(): string {
  if (typeof window === "undefined") return "ui-user"
  try {
    const raw = localStorage.getItem("dw.auth.user")
    if (raw) {
      const u = JSON.parse(raw) as { username?: string; name?: string }
      return u.username ?? u.name ?? "ui-user"
    }
  } catch {
    /* ignore */
  }
  return "ui-user"
}

export function IncidentThread({
  incident,
  proposals,
  messages,
  live,
  phase,
  onReload,
  onOpenLog,
}: {
  incident: Incident
  proposals: IncidentProposal[]
  messages: IncidentMessage[]
  live: IncidentLiveState
  phase: ConnectionPhase
  onReload: () => void
  onOpenLog: (instanceId: string) => void
}) {
  const t = useTranslations("supervision")
  const [busy, setBusy] = useState(false)

  const pendingProposal = useMemo(
    () => proposals.find((p) => p.status === "PENDING") ?? null,
    [proposals],
  )
  const terminal = isTerminal(incident.state)

  const run = async (fn: () => Promise<unknown>, okKey: string) => {
    if (busy) return
    setBusy(true)
    try {
      await fn()
      toast.success(t(okKey))
      onReload()
    } catch (e) {
      toast.error(e instanceof Error ? e.message : t("actionFailed"))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="flex h-full flex-col rounded-[var(--radius)] bg-card">
      {/* header */}
      <div className="flex items-start justify-between gap-2 p-[var(--card-spacing)] pb-2">
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <span className="truncate text-sm font-semibold text-foreground">
              {incident.taskDefName ?? t("unnamedTask")}
            </span>
            <ClassificationBadge classification={incident.classification} />
            <StateBadge state={incident.state} />
          </div>
          {incident.suggestion && (
            <p className="mt-1 text-xs text-muted-foreground">{incident.suggestion}</p>
          )}
        </div>
        <Button
          variant="outline"
          size="xs"
          onClick={() => onOpenLog(incident.latestInstanceId)}
          title={t("openLog")}
        >
          <HugeiconsIcon icon={DocumentCodeIcon} />
          {t("openLog")}
        </Button>
      </div>

      {/* degraded 提示条：断线重连中，已加载消息保留（FR-002） */}
      {phase === "degraded" && (
        <div className="mx-[var(--card-spacing)] mb-1 flex items-center gap-1.5 rounded-[var(--radius)] bg-muted/60 px-2.5 py-1 text-[11px] text-muted-foreground">
          <span className="size-1.5 rounded-full bg-muted-foreground/50" />
          {t("reconnecting")}
        </div>
      )}

      {/* messages */}
      <div className="min-h-0 flex-1 px-[var(--card-spacing)]">
        <DwScroll className="h-full">
          <div className="space-y-2 py-2">
            {messages.map((m) => (
              <MessageBubble key={m.id} msg={m} onOpenLog={onOpenLog} />
            ))}
            {live.thinking.active && (
              <div className="pl-1">
                <ThinkingDots label={live.thinking.label} />
              </div>
            )}
            {live.chips.length > 0 && (
              <div className="flex flex-wrap gap-1 pl-1">
                {live.chips.map((c) => (
                  <ToolChip key={c.chipId} chip={c} />
                ))}
              </div>
            )}
            {live.delta && (
              <AgentBubble>
                <ChatMarkdown content={live.delta.text} streaming />
                <span className="ml-0.5 inline-block h-3 w-px translate-y-0.5 bg-foreground/60 motion-safe:animate-pulse" />
              </AgentBubble>
            )}
          </div>
        </DwScroll>
      </div>

      {/* pending proposal card */}
      {pendingProposal && (
        <ProposalCard
          proposal={pendingProposal}
          busy={busy}
          onApprove={() =>
            run(
              () =>
                api.approveProposal(incident.id, pendingProposal.id, currentUser(), pendingProposal.id),
              "approveOk",
            )
          }
          onReject={() =>
            run(() => api.rejectProposal(incident.id, pendingProposal.id, currentUser()), "rejectOk")
          }
        />
      )}

      {/* action bar + composer */}
      <div className="space-y-2 p-[var(--card-spacing)] pt-2">
        {!terminal && (
          <div className="flex flex-wrap items-center gap-1.5">
            {incident.state === "NEEDS_HUMAN" && (
              <>
                <Button
                  size="xs"
                  disabled={busy}
                  onClick={() => run(() => api.markHandled(incident.id, undefined, currentUser()), "markHandledOk")}
                >
                  <HugeiconsIcon icon={CheckmarkBadge01Icon} />
                  {t("markHandled")}
                </Button>
                <Button
                  variant="outline"
                  size="xs"
                  disabled={busy}
                  onClick={() => run(() => api.reverify(incident.id, currentUser()), "reverifyOk")}
                >
                  <HugeiconsIcon icon={PlayIcon} />
                  {t("reverify")}
                </Button>
              </>
            )}
            <CloseButton
              busy={busy}
              onClose={(reason) => run(() => api.closeIncident(incident.id, reason, currentUser()), "closeOk")}
            />
          </div>
        )}
        <ChatComposer onSend={(text) => api.sendChat(incident.id, text, currentUser()).then(() => undefined)} disabled={terminal} />
      </div>
    </div>
  )
}

// ─── 消息气泡（新增原语，T038 回填 DESIGN.md）───────────────

function MessageBubble({ msg, onOpenLog }: { msg: IncidentMessage; onOpenLog: (id: string) => void }) {
  const t = useTranslations("supervision")
  const fmt = useFormatDateTime()
  const payload = safeParse(msg.payloadJson)

  switch (msg.kind) {
    case "HUMAN_SAY":
      return (
        <div className="flex justify-end">
          <div className="max-w-[80%] rounded-[var(--radius)] bg-primary px-3 py-1.5 text-sm text-primary-foreground">
            {msg.content}
          </div>
        </div>
      )
    case "AGENT_SAY":
      return (
        <AgentBubble>
          <ChatMarkdown content={msg.content ?? ""} />
        </AgentBubble>
      )
    case "AGENT_STEP": {
      const lines = Array.isArray(payload?.evidenceLines) ? (payload!.evidenceLines as string[]) : []
      return (
        <AgentBubble>
          <p className="font-medium">{msg.content}</p>
          {lines.length > 0 && (
            <ul className="mt-1 space-y-0.5">
              {lines.map((l, i) => (
                <li key={i} className="text-xs text-muted-foreground">
                  · {l}
                </li>
              ))}
            </ul>
          )}
        </AgentBubble>
      )
    }
    case "ACTION":
      return (
        <div className="flex items-center gap-2 px-1 text-xs text-muted-foreground">
          <HugeiconsIcon icon={PlayIcon} className="size-3 text-link" />
          <span>{msg.content}</span>
          <span className="text-muted-foreground/60">{fmt(msg.createdAt)}</span>
        </div>
      )
    case "PROPOSAL":
      return (
        <AgentBubble>
          <span className="flex items-center gap-1.5">
            <HugeiconsIcon icon={SecurityCheckIcon} className="size-3.5 text-warning" />
            {msg.content}
          </span>
        </AgentBubble>
      )
    case "SYSTEM":
    default:
      return (
        <div className="flex justify-center">
          <span className="rounded-3xl bg-muted/60 px-2.5 py-0.5 text-[11px] text-muted-foreground">
            {msg.content}
          </span>
        </div>
      )
  }
}

function AgentBubble({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex justify-start">
      <div className="max-w-[85%] rounded-[var(--radius)] bg-muted/60 px-3 py-1.5 text-sm text-foreground">
        {children}
      </div>
    </div>
  )
}

function ProposalCard({
  proposal,
  busy,
  onApprove,
  onReject,
}: {
  proposal: IncidentProposal
  busy: boolean
  onApprove: () => void
  onReject: () => void
}) {
  const t = useTranslations("supervision")
  const [confirming, setConfirming] = useState(false)
  return (
    <div className="mx-[var(--card-spacing)] rounded-[var(--radius)] border border-warning/30 bg-warning/5 p-3">
      <p className="flex items-center gap-1.5 text-sm font-medium text-foreground">
        <HugeiconsIcon icon={SecurityCheckIcon} className="size-4 text-warning" />
        {t("proposalTitle")}
      </p>
      {proposal.changeSummary && (
        <p className="mt-1 text-xs text-muted-foreground">{proposal.changeSummary}</p>
      )}
      <p className="mt-1 text-[11px] text-muted-foreground">
        {t("proposalBaseVersion", { v: proposal.baseVersionNo })}
      </p>
      <div className="mt-2 flex items-center gap-1.5">
        {confirming ? (
          <>
            <span className="text-xs text-muted-foreground">{t("proposalConfirmHint")}</span>
            <Button size="xs" disabled={busy} onClick={onApprove}>
              {t("proposalConfirm")}
            </Button>
            <Button variant="ghost" size="xs" disabled={busy} onClick={() => setConfirming(false)}>
              {t("cancel")}
            </Button>
          </>
        ) : (
          <>
            <Button size="xs" disabled={busy} onClick={() => setConfirming(true)}>
              <HugeiconsIcon icon={CheckmarkBadge01Icon} />
              {t("approve")}
            </Button>
            <Button variant="outline" size="xs" disabled={busy} onClick={onReject}>
              <HugeiconsIcon icon={Cancel01Icon} />
              {t("reject")}
            </Button>
          </>
        )}
      </div>
    </div>
  )
}

function CloseButton({ busy, onClose }: { busy: boolean; onClose: (reason: string) => void }) {
  const t = useTranslations("supervision")
  const [open, setOpen] = useState(false)
  const [reason, setReason] = useState("")
  if (!open) {
    return (
      <Button variant="ghost" size="xs" disabled={busy} onClick={() => setOpen(true)}>
        <HugeiconsIcon icon={Cancel01Icon} />
        {t("closeManual")}
      </Button>
    )
  }
  return (
    <div className="flex items-center gap-1.5">
      <input
        value={reason}
        onChange={(e) => setReason(e.target.value)}
        placeholder={t("closeReasonPlaceholder")}
        className="h-6 rounded-3xl bg-muted px-2.5 text-xs outline-none focus-visible:ring-2 focus-visible:ring-ring/40"
      />
      <Button
        size="xs"
        disabled={busy || !reason.trim()}
        onClick={() => {
          onClose(reason.trim())
          setOpen(false)
          setReason("")
        }}
      >
        {t("closeConfirm")}
      </Button>
      <Button variant="ghost" size="xs" onClick={() => setOpen(false)}>
        {t("cancel")}
      </Button>
    </div>
  )
}

function safeParse(json: string | null): Record<string, unknown> | null {
  if (!json) return null
  try {
    return JSON.parse(json) as Record<string, unknown>
  } catch {
    return null
  }
}
