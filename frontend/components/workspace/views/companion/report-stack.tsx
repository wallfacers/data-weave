"use client"

/**
 * 虚拟管家汇报卡片栈。
 *
 * - 时间倒序堆叠（最新在上）
 * - 未读徽标计数
 * - 整栈可收起/展开
 * - 项目级共享关闭（SSE report:closed → store.removeReport）
 */
import { useState } from "react"
import { useTranslations } from "next-intl"
import { useCompanionStore } from "@/lib/companion/store"
import { ReportCard } from "./report-card"

export function ReportStack() {
  const t = useTranslations("companion")
  const reports = useCompanionStore((s) => s.reports)
  const [hidden, setHidden] = useState(false)

  const unreadCount = reports.filter((r) => r.status === "UNREAD").length

  return (
    <div className="flex flex-col gap-2.5">
      {/* 标题栏 */}
      <div className="flex items-center justify-end gap-2">
        {unreadCount > 0 && (
          <span className="flex h-5 min-w-[18px] items-center justify-center rounded-full bg-destructive px-1.5 text-[11px] font-bold text-destructive-foreground">
            {unreadCount}
          </span>
        )}
        <button
          className="rounded-lg border border-border/50 bg-card/70 px-3.5 py-1.5 text-[13px] text-foreground backdrop-blur-sm hover:border-primary/50"
          onClick={() => setHidden(!hidden)}
        >
          {t("report.title")}
          {unreadCount > 0 && (
            <span className="ml-1.5 inline-flex h-[18px] min-w-[18px] items-center justify-center rounded-full bg-destructive px-1 text-[11px] font-bold text-destructive-foreground">
              {unreadCount}
            </span>
          )}
        </button>
      </div>

      {/* 卡片栈 */}
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
          reports.map((r, i) => (
            <div key={r.id} style={{ animationDelay: `${i * 0.08}s` }}>
              <ReportCard report={r} />
            </div>
          ))
        )}
      </div>
    </div>
  )
}
