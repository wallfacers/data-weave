"use client"

/**
 * Agent 运维举手台卡片：渲染 dataweave.ops.alert 事件。
 *
 * - severity 着色：info / warning / error → 边框与图标
 * - 标题 + 详情 + 关联实例数
 * - suggestedAction 渲染动作按钮（rerun / kill / set-success / backfill），
 *   回调契约①的 batch / backfill 端点，按 outcome 三态分流。
 */

import { useMemo, useState } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  Alert01Icon,
  TimeManagementIcon,
  CheckmarkCircle01Icon,
  PlayIcon,
  StopIcon,
  Loading03Icon,
} from "@hugeicons/core-free-icons"
import { toast } from "sonner"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import {
  useOpsAlertsStore,
  type OpsAlert,
  type OpsAlertKind,
  type OpsAlertSeverity,
  type OpsSuggestedOp,
} from "@/lib/workspace/ops-alerts-store"
import { authFetch, API_BASE, type ApiResponse } from "@/lib/types"
import { BackfillDialog } from "./backfill-dialog"

const SEVERITY_STYLE: Record<OpsAlertSeverity, { icon: typeof Alert01Icon; ring: string; bg: string; text: string }> = {
  info: {
    icon: Alert01Icon,
    ring: "border-info/40",
    bg: "bg-info/10",
    text: "text-info",
  },
  warning: {
    icon: TimeManagementIcon,
    ring: "border-warning/40",
    bg: "bg-warning/10",
    text: "text-warning",
  },
  error: {
    icon: Alert01Icon,
    ring: "border-destructive/40",
    bg: "bg-destructive/10",
    text: "text-destructive",
  },
}

const KIND_LABEL_KEY: Record<OpsAlertKind, string> = {
  INSTANCE_FAILED: "alertKindInstanceFailed",
  SLA_RISK: "alertKindSlaRisk",
  BACKFILL_DONE: "alertKindBackfillDone",
}

interface BatchResponse {
  code: number
  outcome: "EXECUTED" | "PENDING_APPROVAL" | "REJECTED"
  message?: string
}

export function OpsAlertCard({ alert }: { alert: OpsAlert }) {
  const t = useTranslations("ops")
  const resolve = useOpsAlertsStore((s) => s.resolve)
  const [busy, setBusy] = useState(false)
  const [backfillOpen, setBackfillOpen] = useState(false)
  const style = SEVERITY_STYLE[alert.severity]

  const actionLabel = useMemo(() => {
    const op = alert.suggestedAction?.op
    if (!op) return null
    const map: Record<OpsSuggestedOp, string> = {
      rerun: "alertActionRerun",
      kill: "alertActionKill",
      "set-success": "alertActionSetSuccess",
      backfill: "alertActionBackfill",
    }
    return t(map[op] as never)
  }, [alert.suggestedAction, t])

  async function runSuggestedAction() {
    const sa = alert.suggestedAction
    if (!sa) return
    setBusy(true)
    try {
      if (sa.op === "backfill") {
        setBackfillOpen(true)
        return
      }
      const res = await authFetch(`${API_BASE}/api/ops/instances/batch`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ ids: alert.instanceIds, op: sa.op }),
      })
      const json = (await res.json().catch(() => null)) as BatchResponse | null
      if (!json || json.code !== 0) {
        toast.error(t("actionFailed", { label: actionLabel ?? sa.op, msg: json?.message ?? `HTTP ${res.status}` }))
        return
      }
      // ★ 按 outcome 三态分流
      if (json.outcome === "PENDING_APPROVAL") {
        toast.info(`${actionLabel} · ${t("outcomePendingApproval")}`)
      } else if (json.outcome === "REJECTED") {
        toast.error(`${actionLabel} · ${t("outcomeRejected")}`)
      } else {
        toast.success(`${actionLabel} · ${t("outcomeExecuted")}`)
        resolve(alert.id)
      }
    } catch (e) {
      toast.error(t("actionFailed", { label: actionLabel ?? "", msg: e instanceof Error ? e.message : t("networkError") }))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card className={`border ${style.ring}`}>
      <CardHeader className="pb-3">
        <div className="flex items-start justify-between gap-3">
          <div className="flex items-start gap-3">
            <div className={`flex size-9 shrink-0 items-center justify-center rounded-lg ${style.bg} ${style.text}`}>
              <HugeiconsIcon icon={style.icon} className="size-4" />
            </div>
            <div className="flex flex-col gap-1">
              <CardTitle className="text-sm leading-snug">{alert.title}</CardTitle>
              <div className="flex items-center gap-2 text-xs text-muted-foreground">
                <Badge variant="outline" className="h-4 text-[10px]">
                  {t(KIND_LABEL_KEY[alert.kind] as never)}
                </Badge>
                {alert.instanceIds.length > 0 && (
                  <span className="tabular-nums">
                    {t("alertInstances", { count: alert.instanceIds.length })}
                  </span>
                )}
              </div>
            </div>
          </div>
        </div>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        {alert.detail && (
          <p className="text-xs text-muted-foreground">{alert.detail}</p>
        )}
        {alert.suggestedAction && actionLabel && (
          <Button
            size="sm"
            className="h-8 w-full text-xs"
            variant={alert.severity === "error" ? "destructive" : "default"}
            disabled={busy}
            onClick={runSuggestedAction}
          >
            <HugeiconsIcon
              icon={
                alert.suggestedAction.op === "rerun"
                  ? PlayIcon
                  : alert.suggestedAction.op === "kill"
                    ? StopIcon
                    : CheckmarkCircle01Icon
              }
              className="size-3.5"
            />
            {busy ? <HugeiconsIcon icon={Loading03Icon} className="size-3.5 animate-spin" /> : null}
            {actionLabel}
          </Button>
        )}
      </CardContent>
      {alert.suggestedAction?.op === "backfill" && (
        <BackfillDialog
          open={backfillOpen}
          onOpenChange={(open) => {
            setBackfillOpen(open)
            if (!open) resolve(alert.id)
          }}
          initialTargetType={
            (alert.suggestedAction.params?.targetType as "task" | "workflow") ?? "task"
          }
          initialTargetId={(alert.suggestedAction.params?.targetId as number | string) ?? ""}
        />
      )}
    </Card>
  )
}
