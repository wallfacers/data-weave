"use client"

import { useEffect, useMemo, useRef, useState } from "react"
import { CopilotKitProvider, CopilotChat } from "@copilotkit/react-core/v2"
import { HttpAgent } from "@ag-ui/client"
import type { BundledTheme } from "shiki"
import "@copilotkit/react-core/v2/styles.css"

import { CHAT_SHIKI_THEME } from "@/lib/syntax-palette"
import { ApprovalCard, type Approval } from "@/components/agent/approval-card"
import { ResultTable, type ResultData } from "@/components/agent/result-table"
import { useWorkspaceStore } from "@/lib/workspace/store"
import { getConversationId } from "@/lib/workspace/persistence"
import { acceptLanguageHeader } from "@/lib/locale-client"

// 直连后端 Java AG-UI 端点，不跑 Node CopilotKit Runtime。
// 必须用 v2 API：selfManagedAgents 使 hasLocalAgents=true，绕过 runtime 强制要求。
const AGENT_URL =
  process.env.NEXT_PUBLIC_AGENT_URL ?? "http://localhost:8000/agui"
// REST API 走 Next.js rewrite 代理（自动同源，Bearer token 由各组件自行注入）。
const API_BASE = ""
// chat 内联富渲染保留的最近结果表格条数（避免无限堆积）。
const MAX_RESULT_TABLES = 3

/** 逐消息页面上下文（cockpit 缺口①）。 */
export interface AgentPageContext {
  module?: string
  pathname?: string
  taskId?: string
  instanceId?: string
  nodeId?: string
}

export function AgentChat({ context }: { context?: AgentPageContext }) {
  // threadId = 持久化的会话 id，与 Workspace 快照同 key（刷新后对话与工作区指向同一会话）
  const agent = useMemo(
    () =>
      new HttpAgent({
        url: AGENT_URL,
        threadId: getConversationId(),
      }),
    [],
  )
  const [approvals, setApprovals] = useState<Approval[]>([])
  // 结构化结果富渲染（MVP 4.6）：保留最近 N 条含 columns/rows 的 CUSTOM 结果，用 shadcn Table 展示。
  const [results, setResults] = useState<ResultData[]>([])
  const resultSeq = useRef(0)
  const containerRef = useRef<HTMLDivElement>(null)

  // 经 slot 链 CopilotChat → messageView → assistantMessage → markdownRenderer(Streamdown)
  // 把项目语法主题透传给 Streamdown 的 Shiki dual-theme（[light, dark]）。
  const messageView = useMemo(
    () => ({
      assistantMessage: {
        markdownRenderer: {
          shikiTheme: CHAT_SHIKI_THEME as unknown as [BundledTheme, BundledTheme],
        },
      },
    }),
    [],
  )

  // AI 输入框自绘滚动条（Windows Chrome 修复）。
  // 背景：CopilotKit 的 `[data-copilotkit] ::-webkit-scrollbar*` 通配规则命中输入框 <textarea>，
  // 逼它走 WebKit 自定义滚动条引擎——该引擎在 Windows 上恒渲染上下箭头按钮，
  // `::-webkit-scrollbar-button{display:none}` 去不掉；而退回系统原生滚动条，在 Windows
  // 「始终显示滚动条」模式下同样带箭头（系统绘制，CSS 碰不到）。唯一稳的办法：彻底隐藏
  // 原生/webkit 滚动条（见 globals.css，两者都不画箭头），再自绘一根细 taupe 指示条。
  // 滚轮/触摸板/键盘仍走 textarea 原生滚动，指示条只反映位置（pointer-events:none，不可拖拽）。
  useEffect(() => {
    const container = containerRef.current
    if (!container) return
    if (getComputedStyle(container).position === "static") {
      container.style.position = "relative"
    }
    const thumb = document.createElement("div")
    thumb.className = "dw-textarea-thumb"
    thumb.style.display = "none"
    container.appendChild(thumb)

    let raf = 0
    let observed: Element | null = null
    const ro = new ResizeObserver(() => schedule())
    const update = () => {
      raf = 0
      const ta = container.querySelector<HTMLTextAreaElement>(
        '[data-testid="copilot-chat-textarea"]',
      )
      // 始终让 ResizeObserver 盯住当前 textarea（空/对话态切换会重挂它）。
      if (ta && ta !== observed) {
        if (observed) ro.unobserve(observed)
        ro.observe(ta)
        observed = ta
      }
      if (!ta || ta.scrollHeight <= ta.clientHeight + 1) {
        thumb.style.display = "none"
        return
      }
      const cRect = container.getBoundingClientRect()
      const tRect = ta.getBoundingClientRect()
      // 轨道按 textarea 的上下 padding/border 内缩，使指示条行程与文字内容区平齐，
      // 而非贴到输入框外框的最顶/最底。
      const cs = getComputedStyle(ta)
      const insetTop = (parseFloat(cs.borderTopWidth) || 0) + (parseFloat(cs.paddingTop) || 0)
      const insetBottom =
        (parseFloat(cs.borderBottomWidth) || 0) + (parseFloat(cs.paddingBottom) || 0)
      const trackTop = tRect.top - cRect.top + insetTop
      const trackH = tRect.height - insetTop - insetBottom
      const thumbH = Math.max(24, (ta.clientHeight / ta.scrollHeight) * trackH)
      const maxScroll = ta.scrollHeight - ta.clientHeight
      const ratio = maxScroll > 0 ? ta.scrollTop / maxScroll : 0
      thumb.style.top = `${trackTop + ratio * (trackH - thumbH)}px`
      thumb.style.left = `${tRect.right - cRect.left - 6}px`
      thumb.style.height = `${thumbH}px`
      thumb.style.display = "block"
    }
    const schedule = () => {
      if (!raf) raf = requestAnimationFrame(update)
    }

    // scroll 不冒泡 → 捕获阶段听 textarea 滚动；input 听文本/自动撑高；窗口缩放重算。
    container.addEventListener("scroll", schedule, true)
    container.addEventListener("input", schedule, true)
    window.addEventListener("resize", schedule)
    // CopilotKit 在空/对话态切换时会重挂输入框 → 监听子树变动重绑/重算。
    const mo = new MutationObserver(schedule)
    mo.observe(container, { childList: true, subtree: true })
    schedule()

    return () => {
      container.removeEventListener("scroll", schedule, true)
      container.removeEventListener("input", schedule, true)
      window.removeEventListener("resize", schedule)
      ro.disconnect()
      mo.disconnect()
      if (raf) cancelAnimationFrame(raf)
      thumb.remove()
    }
  }, [])

  // 订阅 agent 自定义事件：dataweave.approval → 审批卡片；
  // dataweave.ui.open → Workspace 打开/激活视图（去重由 store 保证，未知 view 由 store 忽略并 warn）。
  useEffect(() => {
    const sub = agent.subscribe({
      onCustomEvent: ({ event }: { event: { name?: string; value?: unknown } }) => {
        if (event?.name === "dataweave.approval" && event.value) {
          setApprovals((prev) => [...prev, event.value as Approval])
        }
        if (event?.name === "dataweave.ui.open" && event.value) {
          const v = event.value as {
            view?: string
            params?: Record<string, unknown>
            activate?: boolean
          }
          if (typeof v.view === "string") {
            useWorkspaceStore.getState().open(v.view, v.params, { activate: v.activate })
          }
        }
        // 结构化结果（dataweave.result / dataweave.fleet 等）：凡含 columns + rows 即 shadcn Table 富渲染。
        const val = event?.value as
          | { kind?: string; title?: string; sql?: string; columns?: unknown; rows?: unknown }
          | undefined
        if (val && Array.isArray(val.columns) && Array.isArray(val.rows)) {
          setResults((prev) =>
            [
              ...prev,
              {
                id: (resultSeq.current += 1),
                kind: val.kind,
                title: val.title,
                sql: val.sql,
                columns: val.columns as string[],
                rows: val.rows as Record<string, unknown>[],
              },
            ].slice(-MAX_RESULT_TABLES),
          )
        }
      },
    })
    return () => sub.unsubscribe()
  }, [agent])

  // 决策完成：移除卡片，并向对话追加说明消息使 agent 续做。
  function handleResolved(approvalId: number | string, msg: string) {
    setApprovals((prev) => prev.filter((a) => a.approvalId !== approvalId))
    try {
      agent.addMessage({
        id: crypto.randomUUID(),
        role: "user",
        content: msg,
      } as Parameters<typeof agent.addMessage>[0])
      void agent.runAgent()
    } catch {
      // 续做失败不阻塞：审批本身已执行。
    }
  }

  // provider properties 透传为后端 forwardedProps（逐消息上下文）。
  const properties = useMemo(() => ({ dataweave: context ?? {} }), [context])

  return (
    <CopilotKitProvider
      selfManagedAgents={{ dataweave: agent }}
      properties={properties}
      // i18n：Accept-Language 必须经 Provider 注入（CopilotKit v2 用 provider.headers 跑 agent run，
      // HttpAgent 构造器的 headers 会被忽略）。函数形式每次请求重求值 → 跟随当前 UI locale（cookie），
      // 无需因 locale 切换而重建 agent。后端按此头本地化 Agent 文案与错误码。
      headers={() => ({ "Accept-Language": acceptLanguageHeader() })}
    >
      <div
        ref={containerRef}
        className="relative mx-auto flex h-full w-full max-w-3xl flex-col"
      >
        {approvals.length > 0 && (
          <div className="flex flex-col gap-2 px-3 pt-3">
            {approvals.map((a) => (
              <ApprovalCard
                key={String(a.approvalId)}
                approval={a}
                apiBase={API_BASE}
                onResolved={handleResolved}
              />
            ))}
          </div>
        )}
        {results.length > 0 && (
          <div className="flex flex-col gap-2 px-3 pt-3">
            {results.map((r) => (
              <ResultTable
                key={r.id}
                data={r}
                onClose={() =>
                  setResults((prev) => prev.filter((x) => x.id !== r.id))
                }
              />
            ))}
          </div>
        )}
        <CopilotChat agentId="dataweave" messageView={messageView} />
      </div>
    </CopilotKitProvider>
  )
}
