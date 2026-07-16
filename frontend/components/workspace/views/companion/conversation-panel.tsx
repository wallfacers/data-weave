"use client"

/**
 * 会话列表面板（073 Foundational T004 + US3 组装）。
 *
 * 右侧单一面板：上=待处理问题列表（{@link ProblemList}），下=统一会话线程
 * （{@link ConversationThread}）。退役 071 汇报卡片栈。
 *
 * US3 锚定：点问题行 → store.anchorReportId 置位 → 本面板顶部显示锚定头
 * （问题标题 + 取消锚定）；锚定切换时拉取该问题往来历史合并进统一线程。
 * 当前锚定问题被（他人）关闭时 store.removeReport 自动回落 anchorReportId=null，
 * 锚定头随之消失（"该问题已处置"提示由 companion-view 的 toast 承载）。
 *
 * 管家视图 DESIGN.md 071 豁免：面板用半透明玻璃容器，不套标准 Card。
 */
import { useEffect } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import { Cancel01Icon, Link01Icon } from "@hugeicons/core-free-icons"

import { Button } from "@/components/ui/button"
import { useCompanionStore } from "@/lib/companion/store"
import { fetchMessages } from "@/lib/companion/api"
import { ProblemList } from "./problem-list"
import { ConversationThread } from "./conversation-thread"

export function ConversationPanel() {
  const t = useTranslations("companion")
  const anchorReportId = useCompanionStore((s) => s.anchorReportId)
  const setAnchor = useCompanionStore((s) => s.setAnchor)
  const reports = useCompanionStore((s) => s.reports)
  const setMessages = useCompanionStore((s) => s.setMessages)

  const anchored = anchorReportId
    ? reports.find((r) => r.id === anchorReportId)
    : undefined

  /* 挂载即经 REST 加载会话历史（FR-004）——不依赖 SSE snapshot，SSE 慢/断也能回看历史；
     与 SSE 实时消息按 id 合并去重（store.setMessages）。 */
  useEffect(() => {
    fetchMessages({ limit: 100 })
      .then((list) => setMessages(list))
      .catch(() => {})
  }, [setMessages])

  /* 锚定切换 → 加载该问题往来历史并入统一线程（setMessages 合并去重，不替换全局） */
  useEffect(() => {
    if (!anchorReportId) return
    fetchMessages({ reportId: anchorReportId, limit: 100 })
      .then((list) => setMessages(list))
      .catch(() => {})
  }, [anchorReportId, setMessages])

  return (
    <div className="flex h-full flex-col gap-2.5 rounded-[var(--radius-lg)] border border-border/50 bg-card/60 p-2.5 backdrop-blur-md">
      {/* 上：待处理问题列表 */}
      <ProblemList />

      {/* US3 锚定头：当前锚定的问题 + 取消锚定 */}
      {anchorReportId && (
        <div className="flex shrink-0 items-center gap-2 rounded-[var(--radius)] border border-primary/30 bg-primary/5 px-2.5 py-1.5 text-[12px]">
          <HugeiconsIcon icon={Link01Icon} className="size-3.5 shrink-0 text-primary" />
          <span className="min-w-0 flex-1 truncate text-muted-foreground">
            <span className="text-primary">{t("conversation.anchorHeader")}</span>
            {" · "}
            {anchored?.title ||
              (anchored
                ? t(`report.domain.${anchored.domain}`)
                : t("conversation.anchorClosed"))}
          </span>
          <Button
            size="icon"
            variant="ghost"
            className="size-5 shrink-0 text-muted-foreground hover:text-foreground"
            onClick={() => setAnchor(null)}
            aria-label={t("conversation.cancelAnchor")}
          >
            <HugeiconsIcon icon={Cancel01Icon} className="size-3" />
          </Button>
        </div>
      )}

      {/* 下：统一会话线程（flex-1 填满剩余高度） */}
      <div className="flex min-h-0 flex-1 flex-col">
        <ConversationThread />
      </div>
    </div>
  )
}
