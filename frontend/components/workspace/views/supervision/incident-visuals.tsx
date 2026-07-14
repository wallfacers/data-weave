"use client"

/**
 * 067 监督席共享视觉原语（新增，T038 回填 DESIGN.md 公共组件目录）：
 * 事故状态徽章 / 分型徽章 / 工具动作 chip / 思考态呼吸点 / 直播连接脉冲。
 * 全部走语义 token（无裸色值）+ `motion-safe:` 动效，`prefers-reduced-motion` 降级为静态状态文本。
 */
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  Alert02Icon,
  CheckmarkCircle02Icon,
  CancelCircleIcon,
  Loading03Icon,
  UserQuestion01Icon,
  ShieldUserIcon,
} from "@hugeicons/core-free-icons"

import { Badge } from "@/components/ui/badge"
import { cn } from "@/lib/utils"
import type { ChipState, IncidentClassification, IncidentState } from "@/lib/supervision/types"

type BadgeVariant = "default" | "secondary" | "destructive" | "success" | "warning" | "outline"

const STATE_VARIANT: Record<IncidentState, BadgeVariant> = {
  OPEN: "secondary",
  ANALYZING: "default",
  ACTING: "default",
  AWAITING_APPROVAL: "warning",
  NEEDS_HUMAN: "destructive",
  RESOLVED: "success",
  DIAG_UNAVAILABLE: "outline",
}

/** 事故状态徽章（i18n `supervision.state.<STATE>`）。 */
export function StateBadge({ state }: { state: IncidentState }) {
  const t = useTranslations("supervision")
  return <Badge variant={STATE_VARIANT[state]}>{t(`state.${state}`)}</Badge>
}

/** 分型徽章（i18n `supervision.classification.<CLS>`）；null=未诊断不渲染。 */
export function ClassificationBadge({ classification }: { classification: IncidentClassification | null }) {
  const t = useTranslations("supervision")
  if (!classification) return null
  return (
    <Badge variant="outline" className="font-normal">
      {t(`classification.${classification}`)}
    </Badge>
  )
}

/**
 * 工具动作 chip（智能感层）：RUNNING 旋转、DONE 打勾、FAILED 打叉。
 * 逐项点亮串联出 Agent 的处理轨迹（读取日志 ✓ → 分析代码 ⟳ → …）。
 */
export function ToolChip({ chip }: { chip: ChipState }) {
  const icon =
    chip.status === "RUNNING" ? Loading03Icon : chip.status === "DONE" ? CheckmarkCircle02Icon : CancelCircleIcon
  const tone =
    chip.status === "RUNNING"
      ? "text-link border-link/30 bg-link/5"
      : chip.status === "DONE"
        ? "text-success border-success/30 bg-success/5"
        : "text-destructive border-destructive/30 bg-destructive/5"
  return (
    <span
      className={cn(
        "inline-flex items-center gap-1 rounded-3xl border px-2 py-0.5 text-xs font-medium",
        tone,
      )}
    >
      <HugeiconsIcon
        icon={icon}
        className={cn("size-3", chip.status === "RUNNING" && "motion-safe:animate-spin")}
      />
      {chip.label}
    </span>
  )
}

/**
 * 思考态呼吸点：三点错峰跳动表达「Agent 正在思考」。
 * `prefers-reduced-motion` 时不跳动，仅静态点 + label 文本表意。
 */
export function ThinkingDots({ label }: { label?: string | null }) {
  const t = useTranslations("supervision")
  return (
    <span className="inline-flex items-center gap-1.5 text-xs text-muted-foreground" role="status">
      <span className="inline-flex gap-0.5">
        {[0, 1, 2].map((i) => (
          <span
            key={i}
            className="size-1.5 rounded-full bg-link motion-safe:animate-bounce"
            style={{ animationDelay: `${i * 150}ms` }}
          />
        ))}
      </span>
      {label ?? t("thinkingDefault")}
    </span>
  )
}

/** 直播连接脉冲：connected 时绿点呼吸，断开时静态灰点 + 文本。 */
export function LiveDot({ connected }: { connected: boolean }) {
  const t = useTranslations("supervision")
  return (
    <span className="inline-flex items-center gap-1.5 text-xs text-muted-foreground">
      <span className="relative inline-flex size-2">
        {connected && (
          <span className="absolute inline-flex size-2 rounded-full bg-success/60 motion-safe:animate-ping" />
        )}
        <span
          className={cn(
            "relative inline-flex size-2 rounded-full",
            connected ? "bg-success" : "bg-muted-foreground/50",
          )}
        />
      </span>
      {connected ? t("live") : t("disconnected")}
    </span>
  )
}

/** 待处理紧迫度图标（NEEDS_HUMAN=告警 / AWAITING_APPROVAL=审批）。 */
export function PendingIcon({ state }: { state: IncidentState }) {
  if (state === "NEEDS_HUMAN") {
    return <HugeiconsIcon icon={Alert02Icon} className="size-4 text-destructive" />
  }
  if (state === "AWAITING_APPROVAL") {
    return <HugeiconsIcon icon={ShieldUserIcon} className="size-4 text-warning" />
  }
  return <HugeiconsIcon icon={UserQuestion01Icon} className="size-4 text-muted-foreground" />
}
