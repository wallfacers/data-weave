"use client"

import { TooltipProvider } from "@/components/ui/tooltip"
import { AgentRail } from "@/components/agent-rail"

/**
 * 双栏外壳：左 = Agent 对话主驾（常驻、可调宽），右 = Workspace 工作区。
 * min-w-0 让工作区可收缩到内容宽度以下，避免溢出视口产生横向滚动条。
 */
export function AppShell({ children }: { children: React.ReactNode }) {
  return (
    <TooltipProvider>
      <div className="flex h-svh min-w-0">
        <AgentRail />
        <main className="flex h-svh min-w-0 flex-1 flex-col overflow-hidden">
          {children}
        </main>
      </div>
    </TooltipProvider>
  )
}
