"use client"

import { useMemo } from "react"
import { CopilotKitProvider, CopilotChat } from "@copilotkit/react-core/v2"
import { HttpAgent } from "@ag-ui/client"
import "@copilotkit/react-core/v2/styles.css"

// 直连后端 Java AG-UI 端点，不跑 Node CopilotKit Runtime。
// 必须用 v2 API：selfManagedAgents 使 hasLocalAgents=true，绕过 runtime 强制要求。
const AGENT_URL =
  process.env.NEXT_PUBLIC_AGENT_URL ?? "http://localhost:8080/agui"

export function AgentChat() {
  const agent = useMemo(() => new HttpAgent({ url: AGENT_URL }), [])

  return (
    <CopilotKitProvider selfManagedAgents={{ dataweave: agent }}>
      <div className="mx-auto flex h-full w-full max-w-3xl flex-col">
        <CopilotChat agentId="dataweave" />
      </div>
    </CopilotKitProvider>
  )
}
