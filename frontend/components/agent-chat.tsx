"use client"

import { useEffect, useMemo, useState } from "react"
import { CopilotKitProvider, CopilotChat } from "@copilotkit/react-core/v2"
import { HttpAgent } from "@ag-ui/client"
import type { BundledTheme } from "shiki"
import "@copilotkit/react-core/v2/styles.css"

import { CHAT_SHIKI_THEME } from "@/lib/syntax-palette"
import { ApprovalCard, type Approval } from "@/components/agent/approval-card"

// 直连后端 Java AG-UI 端点，不跑 Node CopilotKit Runtime。
// 必须用 v2 API：selfManagedAgents 使 hasLocalAgents=true，绕过 runtime 强制要求。
const AGENT_URL =
  process.env.NEXT_PUBLIC_AGENT_URL ?? "http://localhost:8080/agui"
// 审批/REST 基址：去掉 /agui 尾段。
const API_BASE = AGENT_URL.replace(/\/agui\/?$/, "")

/** 逐消息页面上下文（cockpit 缺口①）。 */
export interface AgentPageContext {
  module?: string
  pathname?: string
  taskId?: string
  instanceId?: string
  nodeId?: string
}

export function AgentChat({ context }: { context?: AgentPageContext }) {
  const agent = useMemo(() => new HttpAgent({ url: AGENT_URL }), [])
  const [approvals, setApprovals] = useState<Approval[]>([])

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

  // 订阅 agent 自定义事件：dataweave.approval → 审批卡片。
  useEffect(() => {
    const sub = agent.subscribe({
      onCustomEvent: ({ event }: { event: { name?: string; value?: unknown } }) => {
        if (event?.name === "dataweave.approval" && event.value) {
          setApprovals((prev) => [...prev, event.value as Approval])
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
    >
      <div className="mx-auto flex h-full w-full max-w-3xl flex-col">
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
        <CopilotChat agentId="dataweave" messageView={messageView} />
      </div>
    </CopilotKitProvider>
  )
}
