"use client"

/**
 * 待处理问题列表（073 US2）—— 退役 071 汇报卡片栈，压缩为可扫读的行列表。
 *
 * - 未关闭汇报按时间倒序渲染为 ProblemRow。
 * - 整块可折叠（折叠时仅留标题 + 未读计数徽标）。
 * - 未读计数徽标 = status==="UNREAD" 的汇报数。
 * - 滚动区一律 DwScroll（禁手写 overflow-y-auto）。
 * - 离线补看：挂载时 fetchReports 加载未关闭汇报。
 *
 * i18n 复用既有 companion.report.* 键（report 与 problem 为同一汇报实体，
 * 语义一致）；不新增 messages 键，避免与并行 Agent 在 messages 文件冲突。
 */
import { useState, useEffect, useMemo } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import { ChevronDownIcon, ChevronUpIcon } from "@hugeicons/core-free-icons"
import { Button } from "@/components/ui/button"
import { DwScroll } from "@/components/ui/dw-scroll"
import { useCompanionStore } from "@/lib/companion/store"
import { fetchReports } from "@/lib/companion/api"
import { ProblemRow } from "./problem-row"

export function ProblemList() {
  const t = useTranslations("companion")
  const reports = useCompanionStore((s) => s.reports)
  const setReports = useCompanionStore((s) => s.setReports)
  const [collapsed, setCollapsed] = useState(false)

  /* 离线补看：加载未关闭汇报 */
  useEffect(() => {
    fetchReports().then(setReports).catch(() => {})
  }, [setReports])

  /* 时间倒序（spec FR-008）——不依赖上游传入顺序 */
  const orderedReports = useMemo(
    () =>
      [...reports].sort(
        (a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime(),
      ),
    [reports],
  )

  const unreadCount = reports.filter((r) => r.status === "UNREAD").length

  return (
    <div className="flex flex-col gap-2">
      <div className="flex items-center justify-end">
        <Button
          size="sm"
          variant="outline"
          className="gap-1.5 text-[13px] backdrop-blur-sm"
          onClick={() => setCollapsed((v) => !v)}
          aria-expanded={!collapsed}
        >
          <HugeiconsIcon
            icon={collapsed ? ChevronUpIcon : ChevronDownIcon}
            className="size-3.5"
          />
          <span>{t("report.title")}</span>
          {unreadCount > 0 && (
            <span
              className="ml-0.5 inline-flex h-[18px] min-w-[18px] items-center justify-center rounded-full bg-destructive px-1 text-[11px] font-bold text-destructive-foreground"
              aria-label={String(unreadCount)}
            >
              {unreadCount}
            </span>
          )}
        </Button>
      </div>

      {!collapsed &&
        (orderedReports.length === 0 ? (
          <div className="rounded-[var(--radius)] border border-border/50 bg-card/70 p-3.5 text-center text-xs text-muted-foreground backdrop-blur-md">
            {t("report.empty")}
          </div>
        ) : (
          <DwScroll className="max-h-[40vh] min-h-0">
            <ul className="m-0 flex flex-col gap-2 p-0">
              {orderedReports.map((r) => (
                <ProblemRow key={r.id} report={r} />
              ))}
            </ul>
          </DwScroll>
        ))}
    </div>
  )
}
