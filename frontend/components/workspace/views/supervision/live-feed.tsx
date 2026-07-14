"use client"

/**
 * T036 直播 feed：待处理（AWAITING_APPROVAL/NEEDS_HUMAN）固定置顶区（洪峰不被刷走，FR-015）+
 * 其余按时间倒序滚动流。进行中条目呼吸态 + 工具 chips 逐项点亮 + delta 打字流（全部 motion-safe，
 * prefers-reduced-motion 降级为静态状态文本）。点击卡片下钻线程。
 */
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import { AiBrain01Icon } from "@hugeicons/core-free-icons"

import { DwScroll } from "@/components/ui/dw-scroll"
import { cn } from "@/lib/utils"
import { useFormatDateTime } from "@/hooks/use-format-date-time"
import { LoadingState } from "@/components/workspace/shared/loading-state"
import type { ConnectionPhase, Incident, IncidentLiveState } from "@/lib/supervision/types"
import { isAgentWorking, isPending } from "@/lib/supervision/types"
import { ClassificationBadge, PendingIcon, StateBadge, ThinkingDots, ToolChip } from "./incident-visuals"

export function LiveFeed({
  pending,
  rest,
  liveOf,
  selectedId,
  phase,
  onSelect,
}: {
  pending: Incident[]
  rest: Incident[]
  liveOf: (id: string) => IncidentLiveState
  selectedId: string | null
  phase: ConnectionPhase
  onSelect: (id: string) => void
}) {
  const t = useTranslations("supervision")

  // 首帧未达（connecting）：显示加载态而非「暂无事故」——连接未确认前不冒充真空态（FR-001/SC-001）。
  if (phase === "connecting" && pending.length === 0 && rest.length === 0) {
    return (
      <div className="flex h-full flex-col">
        <LoadingState active variant="centered" />
      </div>
    )
  }

  return (
    <div className="flex h-full flex-col gap-2.5">
      {pending.length > 0 && (
        <div className="shrink-0 space-y-2.5">
          <p className="flex items-center gap-1.5 text-xs font-medium text-destructive">
            <span className="size-1.5 rounded-full bg-destructive motion-safe:animate-pulse" />
            {t("needsYou", { count: pending.length })}
          </p>
          {pending.map((inc) => (
            <IncidentCard
              key={inc.id}
              inc={inc}
              live={liveOf(inc.id)}
              selected={selectedId === inc.id}
              onSelect={onSelect}
            />
          ))}
        </div>
      )}

      <div className="min-h-0 flex-1">
        {rest.length === 0 && pending.length === 0 ? (
          <div className="flex h-full flex-col items-center justify-center gap-2 text-muted-foreground">
            <HugeiconsIcon icon={AiBrain01Icon} className="size-8 opacity-40" />
            <p className="text-sm">{t("feedEmpty")}</p>
          </div>
        ) : (
          <DwScroll className="h-full">
            <div className="space-y-2.5 pr-1">
              {rest.map((inc) => (
                <IncidentCard
                  key={inc.id}
                  inc={inc}
                  live={liveOf(inc.id)}
                  selected={selectedId === inc.id}
                  onSelect={onSelect}
                />
              ))}
            </div>
          </DwScroll>
        )}
      </div>
    </div>
  )
}

function IncidentCard({
  inc,
  live,
  selected,
  onSelect,
}: {
  inc: Incident
  live: IncidentLiveState
  selected: boolean
  onSelect: (id: string) => void
}) {
  const t = useTranslations("supervision")
  const fmt = useFormatDateTime()
  const working = isAgentWorking(inc.state)
  const pending = isPending(inc.state)

  return (
    <button
      type="button"
      onClick={() => onSelect(inc.id)}
      className={cn(
        "w-full rounded-[var(--radius)] bg-card p-[var(--card-spacing)] text-left transition-all",
        "hover:bg-muted/40",
        selected && "ring-2 ring-ring",
        pending && "border-l-2 border-l-destructive",
        pending && inc.state === "AWAITING_APPROVAL" && "border-l-warning",
      )}
    >
      <div className="flex items-start justify-between gap-2">
        <div className="flex min-w-0 items-center gap-2">
          {pending && <PendingIcon state={inc.state} />}
          <span className="truncate text-sm font-medium text-foreground">
            {inc.taskDefName ?? t("unnamedTask")}
          </span>
        </div>
        <div className="flex shrink-0 items-center gap-1.5">
          <ClassificationBadge classification={inc.classification} />
          <StateBadge state={inc.state} />
        </div>
      </div>

      {inc.summary && <p className="mt-1.5 line-clamp-2 text-xs text-muted-foreground">{inc.summary}</p>}

      {/* 智能感层：思考态 + 工具 chips + 打字流（仅进行中或有活动时显示） */}
      {(working || live.chips.length > 0 || live.delta) && (
        <div className="mt-2 space-y-1.5">
          {live.thinking.active && <ThinkingDots label={live.thinking.label} />}
          {live.chips.length > 0 && (
            <div className="flex flex-wrap gap-1">
              {live.chips.map((c) => (
                <ToolChip key={c.chipId} chip={c} />
              ))}
            </div>
          )}
          {live.delta && (
            <p className="text-xs text-foreground/90">
              {live.delta.text}
              <span className="ml-0.5 inline-block h-3 w-px translate-y-0.5 bg-foreground/60 motion-safe:animate-pulse" />
            </p>
          )}
        </div>
      )}

      <div className="mt-2 flex items-center gap-3 text-[11px] text-muted-foreground">
        <span>{fmt(inc.openedAt)}</span>
        {inc.instanceCount > 1 && <span>{t("failCount", { count: inc.instanceCount })}</span>}
        {inc.autoActionCount > 0 && <span>{t("autoActions", { count: inc.autoActionCount })}</span>}
      </div>
    </button>
  )
}
