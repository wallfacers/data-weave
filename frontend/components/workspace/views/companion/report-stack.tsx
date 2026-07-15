"use client"

/**
 * 虚拟管家汇报卡片栈。
 *
 * - 时间倒序堆叠 + 未读徽标 + 整栈可收起/展开
 * - 项目级共享关闭（SSE report:closed → store.removeReport）
 * - 离线补看：fetchReports 加载未关闭汇报
 */
import { useState, useEffect } from "react"
import { useTranslations } from "next-intl"
import { Button } from "@/components/ui/button"
import { useCompanionStore } from "@/lib/companion/store"
import { fetchReports } from "@/lib/companion/api"
import { ReportCard } from "./report-card"

export function ReportStack() {
  const t = useTranslations("companion")
  const reports = useCompanionStore((s) => s.reports)
  const setReports = useCompanionStore((s) => s.setReports)
  const [hidden, setHidden] = useState(false)

  /* 离线补看：加载未关闭汇报 */
  useEffect(() => {
    fetchReports().then(setReports).catch(() => {})
  }, [setReports])

  const unreadCount = reports.filter((r) => r.status === "UNREAD").length

  return (
    <div className="flex flex-col gap-2.5">
      <div className="flex items-center justify-end">
        <Button
          size="sm" variant="outline"
          className="text-[13px] backdrop-blur-sm"
          onClick={() => setHidden(!hidden)}
        >
          {t("report.title")}
          {unreadCount > 0 && (
            <span className="ml-1.5 inline-flex h-[18px] min-w-[18px] items-center justify-center rounded-full bg-destructive px-1 text-[11px] font-bold text-destructive-foreground">
              {unreadCount}
            </span>
          )}
        </Button>
      </div>

      <div
        className={`flex flex-col gap-2.5 overflow-y-auto transition-all duration-400 ease-[cubic-bezier(.2,.8,.2,1)] ${
          hidden ? "translate-x-[110%] opacity-0" : ""
        }`}
        style={{ maxHeight: "calc(100vh - 220px)" }}
      >
        {reports.length === 0 ? (
          <div className="rounded-[var(--radius)] border border-border/50 bg-card/70 p-3.5 text-center text-xs text-muted-foreground backdrop-blur-md">
            {t("report.empty")}
          </div>
        ) : (
          reports.map((r) => (
            <div key={r.id}>
              <ReportCard report={r} />
            </div>
          ))
        )}
      </div>
    </div>
  )
}
