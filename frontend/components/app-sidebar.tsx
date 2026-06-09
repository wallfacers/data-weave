"use client"

import Link from "next/link"
import { usePathname } from "next/navigation"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  AiChat01Icon,
  Analytics01Icon,
  DashboardSquare01Icon,
  GitBranchIcon,
  SparklesIcon,
  WorkflowSquare01Icon,
} from "@hugeicons/core-free-icons"

import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarGroup,
  SidebarGroupContent,
  SidebarGroupLabel,
  SidebarHeader,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
} from "@/components/ui/sidebar"

const nav = [
  { title: "概览", href: "/", icon: DashboardSquare01Icon },
  { title: "Agent 对话", href: "/agent", icon: AiChat01Icon },
  { title: "任务开发", href: "/tasks", icon: WorkflowSquare01Icon },
  { title: "指标体系", href: "/metrics", icon: Analytics01Icon },
  { title: "数据血缘", href: "/lineage", icon: GitBranchIcon },
]

export function AppSidebar() {
  const pathname = usePathname()

  return (
    <Sidebar>
      <SidebarHeader>
        <div className="flex items-center gap-2 px-2 py-1.5">
          <div className="flex size-8 items-center justify-center rounded-lg bg-primary text-primary-foreground">
            <HugeiconsIcon icon={SparklesIcon} />
          </div>
          <div className="flex flex-col">
            <span className="text-sm font-semibold">DataWeave</span>
            <span className="text-xs text-muted-foreground">AI 数据中台</span>
          </div>
        </div>
      </SidebarHeader>
      <SidebarContent>
        <SidebarGroup>
          <SidebarGroupLabel>导航</SidebarGroupLabel>
          <SidebarGroupContent>
            <SidebarMenu>
              {nav.map((item) => (
                <SidebarMenuItem key={item.href}>
                  <SidebarMenuButton
                    isActive={pathname === item.href}
                    tooltip={item.title}
                    render={<Link href={item.href} />}
                  >
                    <HugeiconsIcon icon={item.icon} />
                    <span>{item.title}</span>
                  </SidebarMenuButton>
                </SidebarMenuItem>
              ))}
            </SidebarMenu>
          </SidebarGroupContent>
        </SidebarGroup>
      </SidebarContent>
      <SidebarFooter>
        <div className="px-2 py-1.5 text-xs text-muted-foreground">
          按 <kbd>D</kbd> 切换深浅色
        </div>
      </SidebarFooter>
    </Sidebar>
  )
}
