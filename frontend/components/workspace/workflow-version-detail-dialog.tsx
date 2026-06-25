"use client"

/**
 * 工作流版本详情弹窗：只读展示历史版本的 DAG 结构 + 配置字段。
 */
import { useTranslations } from "next-intl"
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import type { WorkflowDefVersion } from "@/lib/types"
import { useFormatDateTime } from "@/hooks/use-format-date-time"

export interface WorkflowVersionDetailDialogProps {
  open: boolean
  onClose: () => void
  version: WorkflowDefVersion | null
}

export function WorkflowVersionDetailDialog({ open, onClose, version }: WorkflowVersionDetailDialogProps) {
  const t = useTranslations()
  const formatDateTime = useFormatDateTime()

  if (!version) return null

  let dagSummary = "(no DAG data)"
  if (version.dagSnapshotJson) {
    try {
      const snap = JSON.parse(version.dagSnapshotJson)
      const nodes = snap.nodes ?? []
      const edges = snap.edges ?? []
      dagSummary = `${nodes.length} nodes, ${edges.length} edges`
    } catch { /* keep default */ }
  }

  return (
    <Dialog open={open} onOpenChange={(o) => { if (!o) onClose() }}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>{t("versionHistory.detailTitle", { vno: version.versionNo })}</DialogTitle>
        </DialogHeader>
        <div className="space-y-3 text-xs">
          <ConfigItem label={t("workflowConfig.name")} value={version.name} />
          <ConfigItem label={t("workflowConfig.description")} value={version.description ?? "-"} />
          <ConfigItem label={t("workflowConfig.scheduleType")} value={version.scheduleType} />
          <ConfigItem label={t("workflowConfig.cron")} value={version.cron ?? "-"} />
          <div>
            <span className="font-medium text-muted-foreground">DAG:</span>{" "}
            <code className="font-mono">{dagSummary}</code>
          </div>
          {version.remark && (
            <div>
              <span className="font-medium text-muted-foreground">{t("versionHistory.remark")}:</span>{" "}
              {version.remark}
            </div>
          )}
          <div className="text-[10px] text-muted-foreground">
            {t("versionHistory.publishedBy")}: {version.publishedBy ?? "-"} · {formatDateTime(version.publishedAt)}
          </div>
        </div>
      </DialogContent>
    </Dialog>
  )
}

function ConfigItem({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex justify-between">
      <span className="text-muted-foreground">{label}</span>
      <span className="font-mono">{value}</span>
    </div>
  )
}
