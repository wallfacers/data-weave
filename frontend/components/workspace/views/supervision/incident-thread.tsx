"use client"

/**
 * T037 下钻线程：六类消息形态 + 证据下钻链到实例日志 + 人工协同按钮（mark-handled/reverify/close/
 * 批准/驳回）+ 对话输入。Agent 回复的 delta 打字流实时附在消息流末尾。
 */
import { useEffect, useMemo, useRef, useState } from "react"
import { useTranslations } from "next-intl"
import { toast } from "sonner"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  Cancel01Icon,
  CheckmarkBadge01Icon,
  Copy01Icon,
  DocumentCodeIcon,
  PlayIcon,
  SecurityCheckIcon,
  Tick02Icon,
} from "@hugeicons/core-free-icons"

import { Button } from "@/components/ui/button"
import { DwScroll } from "@/components/ui/dw-scroll"
import { cn } from "@/lib/utils"
import { useAuth } from "@/lib/auth"
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
import { groupMessages } from "@/lib/supervision/group-messages"
import { ChatMarkdown } from "@/components/workspace/shared/chat-markdown"
import { ClassificationBadge, DateSeparator, MessageAvatar, StateBadge, ThinkingDots, ToolChip } from "./incident-visuals"
import { ChatComposer } from "./chat-composer"

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
  const { user } = useAuth()
  const selfUsername = user?.username ?? null
  const [busy, setBusy] = useState(false)

  const pendingProposal = useMemo(
    () => proposals.find((p) => p.status === "PENDING") ?? null,
    [proposals],
  )
  const rows = useMemo(() => groupMessages(messages), [messages])
  const terminal = isTerminal(incident.state)
  // Agent 正在产出（有打字流或思考态）→ composer 显示停止键。
  const agentStreaming = live.delta !== null || live.thinking.active

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
            {rows.map((row) =>
              row.type === "date" ? (
                <DateSeparator key={row.key} label={formatDateLabel(row.dateKey, t)} />
              ) : (
                <MessageBubble
                  key={row.key}
                  msg={row.msg}
                  showHeader={row.showHeader}
                  self={row.msg.kind === "HUMAN_SAY" && row.msg.actor === selfUsername}
                />
              ),
            )}
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
              <div className="pl-8">
                <div className="max-w-[85%] rounded-[var(--radius)] bg-muted/60 px-3 py-1.5 text-sm text-foreground">
                  <ChatMarkdown content={live.delta.text} streaming />
                  <span className="ml-0.5 inline-block h-3 w-px translate-y-0.5 bg-foreground/60 motion-safe:animate-pulse" />
                </div>
              </div>
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
              () => api.approveProposal(incident.id, pendingProposal.id, pendingProposal.id),
              "approveOk",
            )
          }
          onReject={() =>
            run(() => api.rejectProposal(incident.id, pendingProposal.id), "rejectOk")
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
                  onClick={() => run(() => api.markHandled(incident.id), "markHandledOk")}
                >
                  <HugeiconsIcon icon={CheckmarkBadge01Icon} />
                  {t("markHandled")}
                </Button>
                <Button
                  variant="outline"
                  size="xs"
                  disabled={busy}
                  onClick={() => run(() => api.reverify(incident.id), "reverifyOk")}
                >
                  <HugeiconsIcon icon={PlayIcon} />
                  {t("reverify")}
                </Button>
              </>
            )}
            <CloseButton
              busy={busy}
              onClose={(reason) => run(() => api.closeIncident(incident.id, reason), "closeOk")}
            />
          </div>
        )}
        <ChatComposer
          onSend={(text) => api.sendChat(incident.id, text).then(() => undefined)}
          onCancel={() => api.cancelAgent(incident.id).then(() => undefined)}
          streaming={agentStreaming}
          disabled={terminal}
        />
      </div>
    </div>
  )
}

// ─── 消息气泡（070 US4：身份/头像/分组/hover 复制；登记 DESIGN.md）───────────────

/** 日期分隔标签本地化：今天/昨天/原始日期。 */
function formatDateLabel(dateKey: string, t: ReturnType<typeof useTranslations>): string {
  const today = new Date()
  const y = new Date(today)
  y.setDate(today.getDate() - 1)
  const iso = (d: Date) => d.toISOString().slice(0, 10)
  if (dateKey === iso(today)) return t("dateToday")
  if (dateKey === iso(y)) return t("dateYesterday")
  return dateKey
}

function MessageBubble({ msg, showHeader, self }: { msg: IncidentMessage; showHeader: boolean; self: boolean }) {
  const t = useTranslations("supervision")
  const fmt = useFormatDateTime()
  const payload = safeParse(msg.payloadJson)
  const displayName = msg.actorName ?? (msg.actor && msg.actor !== "ui-user" ? msg.actor : t("fallbackOperator"))

  switch (msg.kind) {
    case "HUMAN_SAY":
      if (self) {
        return (
          <div className="flex justify-end" title={fmt(msg.createdAt)}>
            <div className="max-w-[80%] rounded-[var(--radius)] bg-primary px-3 py-1.5 text-sm text-primary-foreground">
              {msg.content}
            </div>
          </div>
        )
      }
      return (
        <LeftMessage
          showHeader={showHeader}
          avatar={<MessageAvatar variant="human" name={displayName} />}
          name={displayName}
          title={fmt(msg.createdAt)}
        >
          <div className="rounded-[var(--radius)] bg-muted/60 px-3 py-1.5 text-sm text-foreground">{msg.content}</div>
        </LeftMessage>
      )
    case "AGENT_SAY": {
      const interrupted = payload?.interrupted === true
      return (
        <LeftMessage
          showHeader={showHeader}
          avatar={<MessageAvatar variant="agent" />}
          name={t("agentName")}
          title={fmt(msg.createdAt)}
          hoverActions={<CopyButton text={msg.content ?? ""} />}
        >
          <div className="rounded-[var(--radius)] bg-muted/60 px-3 py-1.5 text-sm text-foreground">
            <ChatMarkdown content={msg.content ?? ""} />
            {interrupted && (
              <span className="mt-1 block text-[11px] italic text-muted-foreground">{t("interrupted")}</span>
            )}
          </div>
        </LeftMessage>
      )
    }
    case "AGENT_STEP": {
      const lines = Array.isArray(payload?.evidenceLines) ? (payload!.evidenceLines as string[]) : []
      return (
        <LeftMessage
          showHeader={showHeader}
          avatar={<MessageAvatar variant="agent" />}
          name={t("agentName")}
          title={fmt(msg.createdAt)}
        >
          <div className="rounded-[var(--radius)] bg-muted/60 px-3 py-1.5 text-sm text-foreground">
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
          </div>
        </LeftMessage>
      )
    }
    case "ACTION":
      return (
        <div className="flex items-center gap-2 px-1 text-xs text-muted-foreground" title={fmt(msg.createdAt)}>
          <HugeiconsIcon icon={PlayIcon} className="size-3 text-link" />
          <span>{msg.content}</span>
          <span className="text-muted-foreground/60">{fmt(msg.createdAt)}</span>
        </div>
      )
    case "PROPOSAL":
      return (
        <LeftMessage
          showHeader={showHeader}
          avatar={<MessageAvatar variant="agent" />}
          name={t("agentName")}
          title={fmt(msg.createdAt)}
        >
          <div className="rounded-[var(--radius)] bg-muted/60 px-3 py-1.5 text-sm text-foreground">
            <span className="flex items-center gap-1.5">
              <HugeiconsIcon icon={SecurityCheckIcon} className="size-3.5 text-warning" />
              {msg.content}
            </span>
          </div>
        </LeftMessage>
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

/** 左对齐消息布局：组首显示头像+姓名头，随后消息仅缩进对齐（分组）。hover 浮现操作条。 */
function LeftMessage({
  showHeader,
  avatar,
  name,
  title,
  hoverActions,
  children,
}: {
  showHeader: boolean
  avatar: React.ReactNode
  name: string
  title?: string
  hoverActions?: React.ReactNode
  children: React.ReactNode
}) {
  return (
    <div className={cn("group/msg flex flex-col", showHeader ? "mt-1" : "-mt-1")}>
      {showHeader && (
        <div className="mb-0.5 flex items-center gap-1.5 pl-0.5">
          {avatar}
          <span className="text-[11px] font-medium text-muted-foreground">{name}</span>
        </div>
      )}
      <div className="flex items-start gap-1.5 pl-8" title={title}>
        <div className="min-w-0 max-w-[85%]">{children}</div>
        {hoverActions && (
          <div className="opacity-0 transition-opacity group-hover/msg:opacity-100">{hoverActions}</div>
        )}
      </div>
    </div>
  )
}

/** Agent 消息 hover 复制原文按钮：2s 对勾确认，幂等，卸载清理定时器。 */
function CopyButton({ text }: { text: string }) {
  const t = useTranslations("supervision")
  const [copied, setCopied] = useState(false)
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  useEffect(() => () => {
    if (timerRef.current) clearTimeout(timerRef.current)
  }, [])
  const copy = () => {
    void navigator.clipboard?.writeText(text).then(() => {
      setCopied(true)
      if (timerRef.current) clearTimeout(timerRef.current)
      timerRef.current = setTimeout(() => setCopied(false), 2000)
    })
  }
  return (
    <button
      type="button"
      onClick={copy}
      aria-label={copied ? t("copied") : t("copyMessage")}
      title={copied ? t("copied") : t("copyMessage")}
      className="mt-0.5 rounded-xs p-1 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
    >
      <HugeiconsIcon icon={copied ? Tick02Icon : Copy01Icon} className="size-3" />
    </button>
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
