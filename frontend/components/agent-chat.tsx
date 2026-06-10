"use client"

import { useMemo } from "react"
import { CopilotKitProvider, CopilotChat } from "@copilotkit/react-core/v2"
import { HttpAgent } from "@ag-ui/client"
import type { BundledTheme } from "shiki"
import "@copilotkit/react-core/v2/styles.css"

import { CHAT_SHIKI_THEME } from "@/lib/syntax-palette"

// 直连后端 Java AG-UI 端点，不跑 Node CopilotKit Runtime。
// 必须用 v2 API：selfManagedAgents 使 hasLocalAgents=true，绕过 runtime 强制要求。
const AGENT_URL =
  process.env.NEXT_PUBLIC_AGENT_URL ?? "http://localhost:8080/agui"

export function AgentChat() {
  const agent = useMemo(() => new HttpAgent({ url: AGENT_URL }), [])

  // 经 slot 链 CopilotChat → messageView → assistantMessage → markdownRenderer(Streamdown)
  // 把项目语法主题透传给 Streamdown 的 Shiki dual-theme（[light, dark]）。与 Monaco 编辑器
  // 共用 lib/syntax-palette 的同一套主题对象，两端高亮一致。Streamdown 类型限定 BundledTheme
  // 名，但运行时底层 shiki createHighlighter 同样接受主题对象，故此处断言透传。
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

  return (
    <CopilotKitProvider selfManagedAgents={{ dataweave: agent }}>
      <div className="mx-auto flex h-full w-full max-w-3xl flex-col">
        <CopilotChat agentId="dataweave" messageView={messageView} />
      </div>
    </CopilotKitProvider>
  )
}
