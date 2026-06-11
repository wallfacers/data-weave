"use client"

import { useEffect } from "react"
import { usePathname, useRouter } from "next/navigation"
import { TooltipProvider } from "@/components/ui/tooltip"
import { AgentRail } from "@/components/agent-rail"
import { useAuth } from "@/lib/auth"

/**
 * 双栏外壳：左 = Agent 对话主驾（常驻、可调宽），右 = Workspace 工作区。
 * min-w-0 让工作区可收缩到内容宽度以下，避免溢出视口产生横向滚动条。
 *
 * 未登录时自动重定向到 /login。
 */
export function AppShell({ children }: { children: React.ReactNode }) {
  const { user, loading } = useAuth()
  const pathname = usePathname()
  const router = useRouter()

  useEffect(() => {
    if (!loading && !user && pathname !== "/login") {
      router.replace("/login")
    }
  }, [loading, user, pathname, router])

  // 登录页不需要双栏 shell
  if (pathname === "/login") {
    return <>{children}</>
  }

  // 加载中或未登录（等待重定向）
  if (loading || !user) {
    return (
      <div className="flex h-svh items-center justify-center bg-background">
        <span className="font-serif text-muted-foreground">加载中…</span>
      </div>
    )
  }

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
