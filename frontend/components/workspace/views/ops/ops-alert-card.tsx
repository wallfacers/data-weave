"use client"

import { useTranslations } from "next-intl"
import { useOpsAlertsStore, type OpsAlert } from "@/lib/workspace/ops-alerts-store"

const KIND_KEY: Record<OpsAlert["kind"], string> = {
  INSTANCE_FAILED: "alertKindInstanceFailed",
  SLA_RISK: "alertKindSlaRisk",
  BACKFILL_DONE: "alertKindBackfillDone",
}

const ACTION_KEY: Record<OpsAlert["kind"] | string, string> = {
  rerun: "alertActionRerun",
  kill: "alertActionKill",
  setSuccess: "alertActionSetSuccess",
  backfill: "alertActionBackfill",
}

/**
 * 「Agent 举手台」单条告警卡。展示告警类别/标题/详情/关联实例数，
 * 并按 suggestedAction 渲染主操作按钮（点击即本地消解；真实运维动作的接线为后续工作）。
 */
export function OpsAlertCard({ alert }: { alert: OpsAlert }) {
  const t = useTranslations("ops")
  const resolve = useOpsAlertsStore((s) => s.resolve)
  const isError = alert.severity === "error"

  return (
    <div
      className={
        "rounded-lg border p-3 " +
        (isError ? "border-destructive/30 bg-destructive/5" : "bg-card")
      }
    >
      <div className="flex items-center gap-2">
        <span
          className={
            "rounded-full px-2 py-0.5 text-xs font-medium " +
            (isError
              ? "bg-destructive/10 text-destructive"
              : "bg-muted text-muted-foreground")
          }
        >
          {t(KIND_KEY[alert.kind])}
        </span>
        {alert.instanceIds.length > 0 && (
          <span className="text-xs text-muted-foreground tabular-nums">
            {t("alertInstances", { count: alert.instanceIds.length })}
          </span>
        )}
      </div>

      <p className="mt-2 text-sm font-medium">{alert.title}</p>
      {alert.detail && (
        <p className="mt-1 text-xs text-muted-foreground">
          <span className="font-medium">{t("alertDetailLabel")}: </span>
          {alert.detail}
        </p>
      )}

      {alert.suggestedAction && (
        <div className="mt-3 flex items-center gap-2">
          <button
            type="button"
            onClick={() => resolve(alert.id)}
            className="rounded-md bg-primary px-2.5 py-1 text-xs font-medium text-primary-foreground hover:bg-primary/90"
          >
            {t(ACTION_KEY[alert.suggestedAction.op] ?? "alertActionRerun")}
          </button>
        </div>
      )}
    </div>
  )
}
