"use client"

/**
 * 版本详情弹窗：只读展示历史版本的完整快照（代码 + 配置字段）。
 */
import { useTranslations } from "next-intl"
import Editor from "@monaco-editor/react"
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import type { TaskDefVersion } from "@/lib/types"

export interface VersionDetailDialogProps {
  open: boolean
  onClose: () => void
  version: TaskDefVersion | null
}

export function VersionDetailDialog({ open, onClose, version }: VersionDetailDialogProps) {
  const t = useTranslations()

  if (!version) return null

  const lang = version.type === "SQL" ? "sql" : "shell"

  return (
    <Dialog open={open} onOpenChange={(o) => { if (!o) onClose() }}>
      <DialogContent className="max-w-2xl max-h-[80vh] flex flex-col">
        <DialogHeader>
          <DialogTitle>{t("versionHistory.detailTitle", { vno: version.versionNo })}</DialogTitle>
        </DialogHeader>
        <div className="flex-1 min-h-0 overflow-auto space-y-3">
          {/* 代码快照 */}
          <div>
            <label className="text-xs font-medium text-muted-foreground">
              {t("taskEditor.content")}
            </label>
            <div className="mt-1 h-[260px] rounded-md border border-border overflow-hidden">
              <Editor
                value={version.content}
                language={lang}
                theme="vs-dark"
                options={{ readOnly: true, minimap: { enabled: false }, lineNumbers: "on", scrollBeyondLastLine: false }}
              />
            </div>
          </div>
          {/* 配置字段只读 */}
          <div className="grid grid-cols-2 gap-2 text-xs">
            <ConfigItem label={t("taskEditor.name")} value={version.name} />
            <ConfigItem label={t("taskEditor.type")} value={version.type} />
            <ConfigItem label={t("taskEditor.priority")} value={String(version.priority)} />
            <ConfigItem label={t("taskEditor.description")} value={version.description ?? "-"} />
            <ConfigItem label={t("taskEditor.timeout")} value={version.timeoutSec != null ? `${version.timeoutSec}s` : "-"} />
            <ConfigItem label={t("taskEditor.retryMax")} value={version.retryMax != null ? String(version.retryMax) : "-"} />
          </div>
          {version.remark && (
            <div className="text-xs text-muted-foreground">
              <span className="font-medium">{t("versionHistory.remark")}:</span> {version.remark}
            </div>
          )}
        </div>
      </DialogContent>
    </Dialog>
  )
}

function ConfigItem({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex flex-col gap-0.5">
      <span className="text-[10px] text-muted-foreground">{label}</span>
      <span className="font-mono">{value}</span>
    </div>
  )
}
