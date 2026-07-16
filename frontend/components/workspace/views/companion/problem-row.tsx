"use client"

/**
 * 待处理问题列表 — 单行（073 US2）。
 *
 * 把 071 汇报卡片压缩为一行：severity 色点 + 标题（缺省回落领域名）+ 领域
 * + 聚合计数 ×N + 本地化时间 + 关闭 + 查看详情。比卡片栈可一屏扫读。
 *
 * - 滚动区由父级 ProblemList 的 DwScroll 承载，行内不自滚。
 * - 关闭：closeReport 成功后乐观 removeReport（即时反馈；与 SSE report:closed
 *   的 removeReport 幂等共存，不冲突）。
 * - 半透明玻璃容器沿用 071 豁免（不套 Card）。
 */
import { useState, useCallback } from "react"
import { useTranslations } from "next-intl"
import { toast } from "sonner"
import { HugeiconsIcon } from "@hugeicons/react"
import { Cancel01Icon, ArrowUpRightIcon } from "@hugeicons/core-free-icons"
import { Button } from "@/components/ui/button"
import { useFormatDateTime } from "@/hooks/use-format-date-time"
import type { ReportView } from "@/lib/companion/types"
import { closeReport } from "@/lib/companion/api"
import { useCompanionStore } from "@/lib/companion/store"
import { useWorkspaceStore } from "@/lib/workspace/store"

const SEVERITY_DOT: Record<string, string> = {
  DANGER: "bg-destructive shadow-[0_0_8px_var(--destructive)]",
  WARN: "bg-warning shadow-[0_0_8px_var(--warning)]",
  OK: "bg-success shadow-[0_0_8px_var(--success)]",
  INFO: "bg-info shadow-[0_0_8px_var(--info)]",
}

interface ProblemRowProps {
  report: ReportView
}

export function ProblemRow({ report }: ProblemRowProps) {
  const t = useTranslations("companion")
  const formatDateTime = useFormatDateTime()
  const removeReport = useCompanionStore((s) => s.removeReport)
  const setAnchor = useCompanionStore((s) => s.setAnchor)
  const anchorReportId = useCompanionStore((s) => s.anchorReportId)
  const openView = useWorkspaceStore((s) => s.open)
  const [closing, setClosing] = useState(false)
  const anchored = anchorReportId === report.id

  const domainLabel = t(`report.domain.${report.domain}`)
  const title = report.title || domainLabel
  const severityDot = SEVERITY_DOT[report.severity] ?? SEVERITY_DOT.INFO
  const unread = report.status === "UNREAD"

  const handleClose = useCallback(async () => {
    setClosing(true)
    try {
      await closeReport(report.id)
      /* 乐观移除：即时从列表消失；SSE report:closed 随后的 removeReport 幂等 */
      removeReport(report.id)
    } catch (e) {
      setClosing(false)
      toast.error(e instanceof Error ? e.message : t("chat.sendFailed"))
    }
  }, [report.id, removeReport, t])

  const handleViewDetail = useCallback(() => {
    openView("supervision", { reportId: report.id })
  }, [openView, report.id])

  return (
    <li
      className={`rounded-[var(--radius)] border bg-card/70 p-2.5 backdrop-blur-md transition-all duration-200 ${
        anchored ? "border-primary/50 ring-1 ring-primary/30" : "border-border/50"
      } ${closing ? "translate-x-[40px] opacity-0" : ""}`}
    >
      <div className="flex items-center gap-2">
        <span className={`size-2 shrink-0 rounded-full ${severityDot}`} aria-hidden />
        {/* 正文点选 → 锚定该问题进会话追问（US3） */}
        <div
          className="min-w-0 flex-1 cursor-pointer"
          role="button"
          tabIndex={0}
          title={t("report.chat")}
          onClick={() => setAnchor(report.id)}
          onKeyDown={(e) => {
            if (e.key === "Enter" || e.key === " ") {
              e.preventDefault()
              setAnchor(report.id)
            }
          }}
        >
          <div
            className={`truncate text-[13px] ${
              unread ? "font-semibold text-foreground" : "font-medium text-muted-foreground"
            }`}
          >
            {title}
            {report.aggregateCount > 1 && (
              <span className="ml-1 text-[11px] text-muted-foreground">
                ×{report.aggregateCount}
              </span>
            )}
          </div>
          <div className="mt-0.5 flex items-center gap-1.5 text-[11px] text-muted-foreground">
            <span>{domainLabel}</span>
            <span aria-hidden>·</span>
            <time dateTime={report.createdAt}>{formatDateTime(report.createdAt)}</time>
          </div>
        </div>
        <Button
          size="icon"
          variant="ghost"
          className="size-6 shrink-0 text-muted-foreground hover:text-foreground"
          onClick={handleViewDetail}
          aria-label={t("report.viewDetail")}
        >
          <HugeiconsIcon icon={ArrowUpRightIcon} className="size-3.5" />
        </Button>
        <Button
          size="icon"
          variant="ghost"
          className="size-6 shrink-0 text-muted-foreground hover:text-foreground"
          onClick={handleClose}
          disabled={closing}
          aria-label={t("report.close")}
        >
          <HugeiconsIcon icon={Cancel01Icon} className="size-3.5" />
        </Button>
      </div>
    </li>
  )
}
