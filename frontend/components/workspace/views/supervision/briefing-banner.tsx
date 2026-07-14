"use client"

/**
 * T035 战况播报横幅：一句话综述 + 内嵌实时数字（点击过滤 feed）+ 展开完整接班报告。
 * 数字来自 SSE 直播/REST 的实时 stats（SC-010 权威），综述来自最近一次播报生成（可滞后）。
 */
import { useState } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import { ArrowDown01Icon, ArrowUp01Icon } from "@hugeicons/core-free-icons"

import { Button } from "@/components/ui/button"
import { cn } from "@/lib/utils"
import { ChatMarkdown } from "@/components/workspace/shared/chat-markdown"
import { LiveDot } from "./incident-visuals"
import type { ConnectionPhase, IncidentState, IncidentStats } from "@/lib/supervision/types"

export type FeedFilterValue = { kind: "all" } | { kind: "pending" } | { kind: "state"; state: IncidentState }

interface StatChip {
  key: string
  value: number
  tone: string
  filter: FeedFilterValue
}

export function BriefingBanner({
  summaryLine,
  stats,
  reportMd,
  connected,
  phase,
  activeFilter,
  onFilter,
}: {
  summaryLine: string | null
  stats: IncidentStats | null
  reportMd: string | null
  connected: boolean
  phase: ConnectionPhase
  activeFilter: FeedFilterValue
  onFilter: (f: FeedFilterValue) => void
}) {
  const t = useTranslations("supervision")
  const [expanded, setExpanded] = useState(false)
  // 首帧未达时不显示「当前无活跃事故」（会误导为真空态）；显示连接中占位。
  const summaryText = phase === "connecting" ? t("connecting") : (summaryLine ?? t("briefingEmpty"))

  const s = stats ?? { active: 0, agentWorking: 0, awaitingApproval: 0, needsHuman: 0, resolvedToday: 0 }
  const chips: StatChip[] = [
    { key: "active", value: s.active, tone: "text-foreground", filter: { kind: "all" } },
    { key: "needsHuman", value: s.needsHuman, tone: "text-destructive", filter: { kind: "state", state: "NEEDS_HUMAN" } },
    { key: "awaitingApproval", value: s.awaitingApproval, tone: "text-warning", filter: { kind: "state", state: "AWAITING_APPROVAL" } },
    { key: "agentWorking", value: s.agentWorking, tone: "text-link", filter: { kind: "state", state: "ACTING" } },
    { key: "resolvedToday", value: s.resolvedToday, tone: "text-success", filter: { kind: "state", state: "RESOLVED" } },
  ]

  const isActive = (f: FeedFilterValue) =>
    f.kind === activeFilter.kind && (f.kind !== "state" || (activeFilter.kind === "state" && f.state === activeFilter.state))

  return (
    <div className="rounded-[var(--radius)] bg-card p-[var(--card-spacing)]">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex min-w-0 items-center gap-3">
          <LiveDot connected={connected} />
          <p className={cn("min-w-0 truncate text-sm font-medium", phase === "connecting" ? "text-muted-foreground" : "text-foreground")}>
            {summaryText}
          </p>
        </div>
        <div className="flex items-center gap-1.5">
          {chips.map((c) => (
            <button
              key={c.key}
              type="button"
              onClick={() => onFilter(isActive(c.filter) && c.filter.kind !== "all" ? { kind: "all" } : c.filter)}
              className={cn(
                "flex items-baseline gap-1 rounded-3xl px-2.5 py-1 text-xs transition-colors",
                isActive(c.filter) ? "bg-muted" : "hover:bg-muted/60",
              )}
              title={t(`stat.${c.key}`)}
            >
              <span className={cn("text-sm font-semibold tabular-nums", c.tone)}>{c.value}</span>
              <span className="text-muted-foreground">{t(`stat.${c.key}`)}</span>
            </button>
          ))}
          {reportMd && (
            <Button variant="ghost" size="icon-sm" onClick={() => setExpanded((e) => !e)} title={t("briefingReport")}>
              <HugeiconsIcon icon={expanded ? ArrowUp01Icon : ArrowDown01Icon} />
            </Button>
          )}
        </div>
      </div>
      {expanded && reportMd && (
        <div className="mt-3 border-t border-border/50 pt-3">
          <ChatMarkdown content={reportMd} />
        </div>
      )}
    </div>
  )
}
