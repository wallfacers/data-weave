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
import { Cancel01Icon } from "@hugeicons/core-free-icons"
import { Button } from "@/components/ui/button"
import { DwScroll } from "@/components/ui/dw-scroll"
import { ErrorState } from "@/components/workspace/shared/error-state"
import { LoadingState } from "@/components/workspace/shared/loading-state"

export interface DetailPanelShellProps {
  title: ReactNode
  onClose: () => void
  loading: boolean
  error: string | null
  onRetry: () => void
  /** 是否有已加载的数据（true → loading 时显示半透明遮罩而非居中 spinner，避免宽度闪烁） */
  hasData: boolean
  children: ReactNode
  /** Header 与 Body 之间的额外内容（不随 Body 滚动），如 Tab 切换条 */
  headerExtra?: ReactNode
  /**
   * Body 是否套 DwScroll 滚动容器（默认 true）。
   * 传 false → children 直接进 flex-1 撑满容器（供自带滚动的内容用，如内嵌日志视图，
   * 避免 OverlayScrollbars 嵌套导致内层 h-full 塌成内容高度）。
   */
  scrollBody?: boolean
}

export function DetailPanelShell({
  title,
  onClose,
  loading,
  error,
  onRetry,
  hasData,
  children,
  headerExtra,
  scrollBody = true,
}: DetailPanelShellProps) {
  const t = useTranslations("ops")

  const body = (
    <>
      {/* 加载中：有旧数据时覆盖半透明遮罩；无旧数据时居中 spinner */}
      {loading && hasData && <LoadingState variant="overlay" active={loading} />}

      {loading && !hasData && <LoadingState active={loading} />}

      {error && <ErrorState message={error} onRetry={onRetry} />}

      {!loading && !error && children}
    </>
  )

  return (
    <div className="flex flex-col h-full min-w-[280px] rounded-[var(--radius-md)] border border-border overflow-hidden bg-card">
      {/* Header */}
      <div className="shrink-0 flex items-center justify-between px-4 pt-3 pb-3 border-b border-border">
        <span className="text-sm font-medium truncate">{title}</span>
        <Button variant="ghost" size="icon-sm" aria-label={t("close")} onClick={onClose}>
          <HugeiconsIcon icon={Cancel01Icon} className="size-4" />
        </Button>
      </div>

      {/* Header 下方额外内容（如 Tab 条，不随 Body 滚动） */}
      {headerExtra}

      {/* Body */}
      {scrollBody ? (
        <DwScroll direction="vertical" className="flex-1 min-h-0 relative" innerClassName="flex flex-col gap-4 p-4">
          {body}
        </DwScroll>
      ) : (
        <div className="relative flex min-h-0 flex-1 flex-col">{body}</div>
      )}
    </div>
  )
}
