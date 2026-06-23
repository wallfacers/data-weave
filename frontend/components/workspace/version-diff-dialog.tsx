"use client"

/**
 * 版本对比弹窗：Monaco DiffEditor 侧对侧 diff 两个版本的文本内容。
 * 通用组件：任务传代码 content（sql/bash），工作流传 DAG 快照 JSON（json）。
 * 由调用方负责把版本对象转换为 VersionDiffInput（选 language + 必要时 pretty-print）。
 */
import { useTranslations } from "next-intl"
import { DiffEditor } from "@monaco-editor/react"
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog"

/** 通用 diff 输入：版本号 + 待对比文本 + Monaco 语言 id。 */
export interface VersionDiffInput {
  versionNo: number
  text: string
  language: string // "sql" | "bash" | "json" …
}

export interface VersionDiffDialogProps {
  open: boolean
  onClose: () => void
  v1: VersionDiffInput | null
  v2: VersionDiffInput | null
}

export function VersionDiffDialog({ open, onClose, v1, v2 }: VersionDiffDialogProps) {
  const t = useTranslations()

  if (!v1 || !v2) return null

  return (
    <Dialog open={open} onOpenChange={(o) => { if (!o) onClose() }}>
      <DialogContent className="max-w-4xl max-h-[85vh] flex flex-col">
        <DialogHeader>
          <DialogTitle>
            {t("versionHistory.diffTitle", { v1: v1.versionNo, v2: v2.versionNo })}
          </DialogTitle>
        </DialogHeader>
        <div className="flex-1 min-h-0 rounded-md border border-border overflow-hidden">
          <DiffEditor
            original={v1.text}
            modified={v2.text}
            language={v1.language}
            theme="vs-dark"
            options={{
              readOnly: true,
              minimap: { enabled: false },
              lineNumbers: "on",
              scrollBeyondLastLine: false,
              renderSideBySide: true,
            }}
            height="100%"
          />
        </div>
      </DialogContent>
    </Dialog>
  )
}
