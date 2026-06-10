"use client"

import { Suspense } from "react"
import { TooltipProvider } from "@/components/ui/tooltip"
import {
  SidebarInset,
  SidebarProvider,
  SidebarTrigger,
} from "@/components/ui/sidebar"
import { AppSidebar } from "@/components/app-sidebar"
import { AgentRail } from "@/components/agent-rail"

/** AgentRail 使用了 useSearchParams，需要 Suspense 包裹 */
function Rail() {
  return (
    <Suspense fallback={null}>
      <AgentRail />
    </Suspense>
  )
}

export function AppShell({ children }: { children: React.ReactNode }) {
  return (
    <TooltipProvider>
      <SidebarProvider>
        <AppSidebar />
        {/* 三栏 flex：左 sidebar | 中内容 | 右 agent rail */}
        <div className="flex min-h-svh flex-1">
          <SidebarInset className="flex-1">
            <header className="flex h-14 shrink-0 items-center gap-2 px-4">
              <SidebarTrigger />
              <span className="text-sm font-medium">DataWeave · 用 Agent 编织数据</span>
            </header>
            <div className="flex min-h-0 flex-1 flex-col overflow-hidden">
              {children}
            </div>
          </SidebarInset>
          <Rail />
        </div>
      </SidebarProvider>
    </TooltipProvider>
  )
}
