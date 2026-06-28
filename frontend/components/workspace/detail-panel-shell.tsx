"use client"

/**
 * 公共侧面板外壳 —— DAG 弹窗右侧详情面板的统一骨架。
 *
 * 封装 Header（标题 + 关闭按钮）+ Body（DwScroll + 内容区）+ loading 半透明遮罩。
 * NodeDetailPanel 和 InstanceDetailSidePanel 共用此骨架，通过 children 注入具体内容。
 */
import type { ReactNode } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import { Cancel01Icon, RefreshIcon } from "@hugeicons/core-free-icons"
import { Button } from "@/components/ui/button"
import { DwScroll } from "@/components/ui/dw-scroll"
import { ErrorState } from "@/components/workspace/shared/error-state"

export interface DetailPanelShellProps {
  title: ReactNode
  onClose: () => void
  loading: boolean
  error: string | null
  onRetry: () => void
  /** 是否有已加载的数据（true → loading 时显示半透明遮罩而非居中 spinner，避免宽度闪烁） */
  hasData: boolean
  children: ReactNode
}

export function DetailPanelShell({
  title,
  onClose,
  loading,
  error,
  onRetry,
  hasData,
  children,
}: DetailPanelShellProps) {
  const t = useTranslations("ops")

  return (
    <div className="flex flex-col h-full min-w-[280px] rounded-[var(--radius-md)] border border-border overflow-hidden bg-card">
      {/* Header */}
      <div className="shrink-0 flex items-center justify-between px-4 py-3 border-b border-border">
        <span className="text-sm font-medium truncate">{title}</span>
        <Button variant="ghost" size="icon-sm" aria-label={t("close")} onClick={onClose}>
          <HugeiconsIcon icon={Cancel01Icon} className="size-4" />
        </Button>
      </div>

      {/* Body */}
      <DwScroll direction="vertical" className="flex-1 min-h-0 relative" innerClassName="flex flex-col gap-4 p-4">
        {/* 加载中：有旧数据时覆盖半透明遮罩；无旧数据时不渲染（避免宽度闪烁） */}
        {loading && hasData && (
          <div className="absolute inset-0 z-10 flex items-center justify-center bg-card/60">
            <div className="flex items-center gap-2 text-muted-foreground">
              <HugeiconsIcon icon={RefreshIcon} className="size-5 animate-spin" />
              <span className="text-xs">Loading…</span>
            </div>
          </div>
        )}

        {loading && !hasData && (
          <div className="flex flex-col items-center justify-center gap-2 py-12 text-muted-foreground">
            <HugeiconsIcon icon={RefreshIcon} className="size-5 animate-spin" />
            <span className="text-xs">Loading…</span>
          </div>
        )}

        {error && <ErrorState message={error} onRetry={onRetry} />}

        {!loading && !error && children}
      </DwScroll>
    </div>
  )
}
