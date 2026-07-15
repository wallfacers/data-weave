"use client"

/**
 * 虚拟管家汇报卡片。
 *
 * - 严重度色点 + 标题 + 时间 + 关闭按钮
 * - 展开/折叠迷你对话（锚定该汇报上下文）
 * - 查看详情跳转（FR-019：监督席或对象详情）
 * - 聚合计数展示
 */
import { useState, useRef, useCallback } from "react"
import { useTranslations } from "next-intl"
import type { ReportView } from "@/lib/companion/types"
import { closeReport } from "@/lib/companion/api"

const SEVERITY_STYLE: Record<string, string> = {
  DANGER: "bg-destructive shadow-[0_0_8px_var(--destructive)]",
  WARN: "bg-warning shadow-[0_0_8px_var(--warning)]",
  OK: "bg-success shadow-[0_0_8px_var(--success)]",
  INFO: "bg-info shadow-[0_0_8px_var(--info)]",
}

interface ReportCardProps {
  report: ReportView
  onClosed?: (id: string) => void
}

export function ReportCard({ report, onClosed }: ReportCardProps) {
  const t = useTranslations("companion")
  const [closing, setClosing] = useState(false)
  const [chatOpen, setChatOpen] = useState(false)
  const [messages, setMessages] = useState<{ role: "agent" | "user"; text: string }[]>([])
  const [inputValue, setInputValue] = useState("")
  const inputRef = useRef<HTMLInputElement>(null)

  const handleClose = useCallback(async () => {
    setClosing(true)
    try {
      await closeReport(report.id)
    } catch {
      // SSE report:closed 事件会触发 store.removeReport
    }
    setTimeout(() => onClosed?.(report.id), 300)
  }, [report.id, onClosed])

  const handleSend = useCallback(() => {
    const v = inputValue.trim()
    if (!v) return
    setMessages((prev) => [...prev, { role: "user", text: v }])
    setInputValue("")
    // TODO T026: 真实 sendChat API + SSE 流式接入
    setTimeout(() => {
      setMessages((prev) => [
        ...prev,
        { role: "agent", text: "收到，这是原型环境——正式版我会基于 workhorse 会话给出真实回复。" },
      ])
    }, 500)
  }, [inputValue])

  const severityDot = SEVERITY_STYLE[report.severity] ?? SEVERITY_STYLE.INFO

  return (
    <div
      className={`rounded-[var(--radius)] border border-border/50 bg-card/70 p-3.5 backdrop-blur-md transition-all duration-300 ${
        closing ? "translate-x-[60px] opacity-0" : "animate-[cardIn_.45s_cubic-bezier(.2,.8,.2,1)_both]"
      }`}
    >
      {/* 头部：严重度点 + 标题 + 时间 + 关闭 */}
      <div className="flex items-center gap-2">
        <span className={`size-2 shrink-0 rounded-full ${severityDot}`} />
        <span className="flex-1 text-[13.5px] font-semibold">
          {t(`report.domain.${report.domain}`)}
        </span>
        <span className="text-[11px] text-muted-foreground">
          {report.createdAt}
        </span>
        <button
          className="rounded-md px-1 py-0.5 text-[15px] leading-none text-muted-foreground hover:bg-muted hover:text-foreground"
          onClick={handleClose}
          aria-label={t("report.close")}
        >
          ✕
        </button>
      </div>

      {/* 正文摘要 */}
      <div className="mt-2.5 text-[12.5px] leading-relaxed text-muted-foreground">
        {report.summary}
      </div>

      {/* 操作栏 */}
      <div className="mt-2.5 flex gap-2">
        <button
          className="rounded-lg border border-border/50 bg-primary/5 px-3 py-1.5 text-xs text-primary hover:bg-primary/10"
          onClick={() => setChatOpen(!chatOpen)}
        >
          {t("report.chat")}
        </button>
        <button className="rounded-lg border border-border/50 bg-transparent px-3 py-1.5 text-xs text-muted-foreground hover:text-foreground">
          {t("report.viewDetail")}
        </button>
      </div>

      {/* 迷你对话 */}
      {chatOpen && (
        <div className="mt-3 border-t border-border/20 pt-2.5">
          {/* 消息列表 */}
          <div className="max-h-[200px] space-y-2 overflow-y-auto">
            {messages.map((m, i) => (
              <div key={i} className="flex gap-2 text-[12.5px] leading-relaxed">
                <span
                  className={`flex size-[22px] shrink-0 items-center justify-center rounded-full text-[10px] font-bold ${
                    m.role === "agent"
                      ? "bg-primary/10 text-primary"
                      : "bg-info/10 text-info"
                  }`}
                >
                  {m.role === "agent" ? "V" : "我"}
                </span>
                <span className="text-muted-foreground">{m.text}</span>
              </div>
            ))}
          </div>
          {/* 输入框 */}
          <div className="mt-2.5 flex gap-2">
            <input
              ref={inputRef}
              className="flex-1 rounded-lg border border-border/50 bg-background/50 px-2.5 py-1.5 text-[12.5px] text-foreground outline-none focus:border-primary/50"
              placeholder="就这项汇报继续问 Vega…"
              value={inputValue}
              onChange={(e) => setInputValue(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter" && !e.nativeEvent.isComposing) handleSend()
              }}
            />
            <button
              className="rounded-lg border border-border/50 bg-primary/5 px-3 py-1.5 text-xs text-primary hover:bg-primary/10"
              onClick={handleSend}
            >
              {t("chat.send")}
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
