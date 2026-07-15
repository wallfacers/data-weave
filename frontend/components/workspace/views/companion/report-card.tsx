"use client"

/**
 * 虚拟管家汇报卡片。
 *
 * - severity 色点 + 标题 + aggregateCount + 时间 + 关闭按钮
 * - 展开/折叠迷你对话（锚定该汇报上下文，经 SSE message/delta/end 流式渲染）
 * - FR-019：查看详情跳转监督席或对象详情
 * - M5：关闭失败 toast 后端本地化消息 + 恢复 closing 态
 */
import { useState, useCallback, useMemo } from "react"
import { useTranslations } from "next-intl"
import { toast } from "sonner"
import { HugeiconsIcon } from "@hugeicons/react"
import { Cancel01Icon } from "@hugeicons/core-free-icons"
import { Button } from "@/components/ui/button"
import { ChatComposer } from "@/components/workspace/views/supervision/chat-composer"
import { ChatMarkdown } from "@/components/workspace/shared/chat-markdown"
import type { ReportView } from "@/lib/companion/types"
import { closeReport, sendChat, cancelChat } from "@/lib/companion/api"
import { useCompanionStore } from "@/lib/companion/store"
import { useWorkspaceStore } from "@/lib/workspace/store"

const SEVERITY_STYLE: Record<string, string> = {
  DANGER: "bg-destructive shadow-[0_0_8px_var(--destructive)]",
  WARN: "bg-warning shadow-[0_0_8px_var(--warning)]",
  OK: "bg-success shadow-[0_0_8px_var(--success)]",
  INFO: "bg-info shadow-[0_0_8px_var(--info)]",
}

interface ReportCardProps {
  report: ReportView
}

export function ReportCard({ report }: ReportCardProps) {
  const t = useTranslations("companion")
  const [closing, setClosing] = useState(false)
  const [chatOpen, setChatOpen] = useState(false)
  const [streaming, setStreaming] = useState(false)

  /* 关闭 — M5：失败恢复 + toast 后端本地化消息 */
  const handleClose = useCallback(async () => {
    setClosing(true)
    try {
      await closeReport(report.id)
    } catch (e) {
      setClosing(false)
      toast.error(e instanceof Error ? e.message : t("chat.sendFailed"))
    }
  }, [report.id, t])

  /* 卡片内发送 — 锚定 reportId */
  const handleSend = useCallback(async (text: string) => {
    setStreaming(true)
    try { await sendChat({ content: text, reportId: report.id }) }
    catch (e) { toast.error(e instanceof Error ? e.message : t("chat.sendFailed")) }
    finally { setStreaming(false) }
  }, [report.id, t])

  const handleCancel = useCallback(async () => {
    try { await cancelChat() } catch (e) {
      toast.error(e instanceof Error ? e.message : t("chat.sendFailed"))
    }
    setStreaming(false)
  }, [t])

  /* M7: 查看详情跳转（FR-019：跳监督席或对象详情） */
  const openView = useWorkspaceStore((s) => s.open)
  const handleViewDetail = useCallback(() => {
    openView("supervision", { reportId: report.id })
  }, [openView, report.id])

  /* 此汇报的 SSE 消息（filter by reportId）。
     selector 只取原始数组引用,filter 放 useMemo——selector 内 filter 每渲染返回新数组
     会触发 getSnapshot 无限循环(评审收口实测崩视图)。 */
  const allMessages = useCompanionStore((s) => s.messages)
  const chatMessages = useMemo(
    () => allMessages.filter((m) => m.reportId === report.id),
    [allMessages, report.id]
  )

  const severityDot = SEVERITY_STYLE[report.severity] ?? SEVERITY_STYLE.INFO

  return (
    <div
      className={`rounded-[var(--radius)] border border-border/50 bg-card/70 p-3.5 backdrop-blur-md transition-all duration-300 ${
        closing ? "translate-x-[60px] opacity-0" : ""
      }`}
    >
      {/* 头部:标题=汇报 title(缺省回落领域名),领域+本地化时间为辅 */}
      <div className="flex items-center gap-2">
        <span className={`size-2 shrink-0 rounded-full ${severityDot}`} />
        <span className="flex-1 text-[13.5px] font-semibold">
          {report.title || t(`report.domain.${report.domain}`)}
          {report.aggregateCount > 1 && (
            <span className="ml-1 text-[11px] text-muted-foreground">×{report.aggregateCount}</span>
          )}
        </span>
        <span className="shrink-0 text-[11px] text-muted-foreground">
          {t(`report.domain.${report.domain}`)} ·{" "}
          {new Date(report.createdAt).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}
        </span>
        <Button
          size="icon" variant="ghost"
          className="size-6 text-muted-foreground hover:text-foreground"
          onClick={handleClose}
          disabled={closing}
          aria-label={t("report.close")}
        >
          <HugeiconsIcon icon={Cancel01Icon} className="size-3.5" />
        </Button>
      </div>

      {/* 正文 */}
      <div className="mt-2.5 text-[12.5px] leading-relaxed text-muted-foreground">
        {report.summary || report.title}
      </div>

      {/* 操作栏 */}
      <div className="mt-2.5 flex gap-2">
        <Button size="sm" variant="outline" onClick={() => setChatOpen(!chatOpen)}>
          {t("report.chat")}
        </Button>
        <Button size="sm" variant="ghost" onClick={handleViewDetail}>
          {t("report.viewDetail")}
        </Button>
      </div>

      {/* 迷你对话 — B3 真接线：ChatMarkdown + ChatComposer + SSE 消息渲染 */}
      {chatOpen && (
        <div className="mt-3 border-t border-border/20 pt-2.5">
          <div className="max-h-[240px] space-y-2 overflow-y-auto">
            {chatMessages.length === 0 && (
              <div className="text-[12px] text-muted-foreground">{t("chat.placeholder")}</div>
            )}
            {chatMessages.map((m) => (
              <div key={m.id} className="flex gap-2 text-[12.5px] leading-relaxed">
                <span className={`flex size-[22px] shrink-0 items-center justify-center rounded-full text-[10px] font-bold ${
                  m.role === "AGENT" ? "bg-primary/10 text-primary" : "bg-info/10 text-info"
                }`}>
                  {m.role === "AGENT" ? "V" : m.actorName?.charAt(0) ?? "?"}
                </span>
                <div className="min-w-0 flex-1 text-muted-foreground">
                  <ChatMarkdown content={m.content} />
                </div>
              </div>
            ))}
          </div>
          <div className="mt-2.5">
            <ChatComposer
              onSend={handleSend}
              onCancel={handleCancel}
              streaming={streaming}
            />
          </div>
        </div>
      )}
    </div>
  )
}
