"use client"

/**
 * 工单时间线抽屉 —— 复用 DetailPanelShell（与 DAG/日志详情同款风格）。
 *
 * 064 重构：替代旧 TimelineDrawer（手写 fixed right-0 w-80 div），
 * 使用 Dialog + DetailPanelShell 模式，DwScroll 细条浮叠滚动。
 *
 * 设计约束（DESIGN.md）：语义 token、无分割线、hugeicons、不手写 dark:。
 */

import { Dialog, DialogContent } from "@/components/ui/dialog"
import { DetailPanelShell } from "@/components/workspace/detail-panel-shell"
import { HugeiconsIcon } from "@hugeicons/react"
import { Clock01Icon } from "@hugeicons/core-free-icons"
import type { IncidentDetail } from "@/lib/incident-api"

const KIND_ICONS: Record<string, string> = {
  SIGNAL: "⚡",
  STATE_CHANGE: "🔄",
  ACTION: "▶",
  APPROVAL: "✓",
  NOTE: "📝",
}

export interface IncidentTimelineDialogProps {
  incidentId: number
  open: boolean
  onOpenChange: (open: boolean) => void
  detail: IncidentDetail | null
  loading: boolean
  t: ReturnType<typeof import("next-intl").useTranslations<"incidents">>
}

export function IncidentTimelineDialog({
  incidentId: _incidentId,
  open,
  onOpenChange,
  detail,
  loading,
  t,
}: IncidentTimelineDialogProps) {
  const hasData = detail !== null
  const timeline = detail?.timeline ?? []

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-[90vw] max-h-[90vh] w-[90vw] h-[90vh] flex flex-col p-0">
        <DetailPanelShell
          title={detail?.incident?.title ?? t("timeline.title")}
          onClose={() => onOpenChange(false)}
          loading={loading}
          error={null}
          onRetry={() => {}}
          hasData={hasData}
          scrollBody={true}
        >
          {timeline.length === 0 ? (
            <div className="flex flex-col items-center justify-center gap-2 py-12 text-muted-foreground">
              <HugeiconsIcon icon={Clock01Icon} className="size-8" />
              <p className="text-sm">{t("timeline.empty")}</p>
            </div>
          ) : (
            <div className="flex flex-col gap-3">
              {timeline.map((entry) => (
                <div key={entry.seq} className="flex gap-2 text-xs">
                  <span className="mt-0.5 shrink-0">{KIND_ICONS[entry.kind] ?? "·"}</span>
                  <div className="flex flex-col gap-0.5 min-w-0">
                    <span className="text-muted-foreground">
                      {t(`timeline.kind.${entry.kind}` as never)} ·{" "}
                      {entry.actor === "system" ? t("timeline.actorSystem") : entry.actor}
                    </span>
                    {entry.payload && Object.keys(entry.payload).length > 0 && (
                      <span className="text-muted-foreground/70 truncate">
                        {JSON.stringify(entry.payload)}
                      </span>
                    )}
                    <span className="text-muted-foreground/50 tabular-nums">{entry.createdAt}</span>
                  </div>
                </div>
              ))}
            </div>
          )}
        </DetailPanelShell>
      </DialogContent>
    </Dialog>
  )
}
