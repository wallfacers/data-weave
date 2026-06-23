"use client"

/**
 * 版本历史面板（通用组件，任务和工作流共用）。
 * 展示按版本号倒序排列的发布版本列表，支持查看/回滚/对比操作。
 */
import { useState } from "react"
import { useTranslations } from "next-intl"
import { Button } from "@/components/ui/button"
import { Badge } from "@/components/ui/badge"

export interface VersionInfo {
  versionNo: number
  publishedAt: string
  publishedBy?: number | null
  remark?: string | null
}

export interface VersionHistoryPanelProps {
  versions: VersionInfo[]
  currentVersionNo: number
  onView: (version: VersionInfo) => void
  onRollback: (version: VersionInfo) => void
  onDiff: (v1: VersionInfo, v2: VersionInfo) => void
}

export function VersionHistoryPanel({
  versions,
  currentVersionNo,
  onView,
  onRollback,
  onDiff,
}: VersionHistoryPanelProps) {
  const t = useTranslations()
  const [selected, setSelected] = useState<Set<number>>(new Set())

  if (versions.length === 0) {
    return (
      <div className="flex flex-1 items-center justify-center p-6 text-sm text-muted-foreground">
        {t("versionHistory.empty")}
      </div>
    )
  }

  function toggle(vno: number) {
    setSelected((prev) => {
      const next = new Set(prev)
      if (next.has(vno)) {
        next.delete(vno)
      } else {
        // 最多选 2 个
        if (next.size >= 2) return prev
        next.add(vno)
      }
      return next
    })
  }

  function handleDiff() {
    const arr = Array.from(selected).sort((a, b) => a - b)
    if (arr.length !== 2) return
    const v1 = versions.find((v) => v.versionNo === arr[0])
    const v2 = versions.find((v) => v.versionNo === arr[1])
    if (v1 && v2) onDiff(v1, v2)
  }

  const selectedArr = Array.from(selected)
  const canDiff = selectedArr.length === 2
  const isCurrent = (vno: number) => vno === currentVersionNo

  return (
    <div className="flex flex-col gap-2 p-3">
      {/* 对比操作栏 */}
      {selected.size > 0 && (
        <div className="flex items-center gap-2 rounded-md bg-muted/50 px-2 py-1.5">
          <span className="text-xs text-muted-foreground">
            {t("versionHistory.selectedCount", { count: selected.size })}
          </span>
          <Button
            size="sm"
            variant="secondary"
            className="ml-auto h-6 text-xs"
            disabled={!canDiff}
            onClick={handleDiff}
          >
            {t("versionHistory.diff")}
          </Button>
        </div>
      )}

      {/* 版本列表 */}
      <div className="flex flex-col gap-1.5">
        {versions.map((v) => (
          <div
            key={v.versionNo}
            className="flex flex-col gap-0.5 rounded-md border border-border p-2"
          >
            <div className="flex items-center gap-1.5">
              <input
                type="checkbox"
                checked={selected.has(v.versionNo)}
                onChange={() => toggle(v.versionNo)}
                className="size-3.5 accent-primary"
              />
              <span className="text-xs font-medium">
                v{v.versionNo}
              </span>
              {isCurrent(v.versionNo) && (
                <Badge variant="success" className="h-4 px-1 text-[10px]">
                  {t("versionHistory.current")}
                </Badge>
              )}
              <span className="ml-auto text-[10px] text-muted-foreground">
                {v.publishedAt?.slice(0, 16)}
              </span>
            </div>
            {v.remark && (
              <p className="text-[11px] text-muted-foreground truncate">{v.remark}</p>
            )}
            <div className="flex gap-1.5 mt-1">
              <Button
                size="sm"
                variant="ghost"
                className="h-6 px-1.5 text-[10px]"
                onClick={() => onView(v)}
              >
                {t("versionHistory.view")}
              </Button>
              {!isCurrent(v.versionNo) && (
                <Button
                  size="sm"
                  variant="ghost"
                  className="h-6 px-1.5 text-[10px] text-destructive hover:text-destructive"
                  onClick={() => onRollback(v)}
                >
                  {t("versionHistory.rollback")}
                </Button>
              )}
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}
