"use client"

import { useEffect } from "react"
import { useTranslations } from "next-intl"
import { usePathname, useRouter } from "next/navigation"
import { TooltipProvider } from "@/components/ui/tooltip"
import { SidePanel } from "@/components/side-panel/side-panel"
import { useAuth } from "@/lib/auth"

/**
 * 工作区外壳：Workspace 多 tab 占满主区，右侧 SidePanel 常驻。
 * min-w-0 让工作区可收缩到内容宽度以下，避免溢出视口产生横向滚动条。
 *
 * 未登录时自动重定向到 /login。
 */
export function AppShell({ children }: { children: React.ReactNode }) {
  const { user, loading } = useAuth()
  const t = useTranslations("common")
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
        <span className="font-serif text-muted-foreground">{t("loading")}</span>
      </div>
    )
  }

  return (
    <TooltipProvider>
      <div className="flex h-svh min-w-0">
        <main className="flex h-svh min-w-0 flex-1 flex-col overflow-hidden">
          {children}
        </main>
        <SidePanel />
      </div>
    </TooltipProvider>
  )
}
