"use client"

import Link from "next/link"
import { usePathname } from "next/navigation"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  Analytics01Icon,
  BugIcon,
  Calendar01Icon,
  DashboardSquare01Icon,
  DatabaseSyncIcon,
  CatalogueIcon,
  GitBranchIcon,
  ServerStackIcon,
  Shield01Icon,
  SparklesIcon,
  ServiceIcon,
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

const topItem = { title: "驾驶舱", href: "/", icon: DashboardSquare01Icon }

const groups = [
  {
    label: "数据研发",
    items: [
      { title: "任务开发", href: "/tasks", icon: WorkflowSquare01Icon },
      { title: "调度运维", href: "/ops", icon: Calendar01Icon },
      { title: "数据集成", href: "/integration", icon: DatabaseSyncIcon },
    ],
  },
  {
    label: "数据资产",
    items: [
      { title: "指标体系", href: "/metrics", icon: Analytics01Icon },
      { title: "数据血缘", href: "/lineage", icon: GitBranchIcon },
      { title: "资产目录", href: "/catalog", icon: CatalogueIcon },
    ],
  },
  {
    label: "资源与诊断",
    items: [
      { title: "集群机器", href: "/fleet", icon: ServerStackIcon },
      { title: "失败诊断", href: "/diagnosis", icon: BugIcon },
      { title: "数据质量", href: "/quality", icon: Shield01Icon },
      { title: "数据服务", href: "/service", icon: ServiceIcon },
    ],
  },
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
        {/* 顶部：驾驶舱 */}
        <SidebarGroup>
          <SidebarGroupContent>
            <SidebarMenu>
              <SidebarMenuItem>
                <SidebarMenuButton
                  isActive={pathname === topItem.href}
                  tooltip={topItem.title}
                  render={<Link href={topItem.href} />}
                >
                  <HugeiconsIcon icon={topItem.icon} />
                  <span>{topItem.title}</span>
                </SidebarMenuButton>
              </SidebarMenuItem>
            </SidebarMenu>
          </SidebarGroupContent>
        </SidebarGroup>

        {/* 分组菜单 */}
        {groups.map((group) => (
          <SidebarGroup key={group.label}>
            <SidebarGroupLabel>{group.label}</SidebarGroupLabel>
            <SidebarGroupContent>
              <SidebarMenu>
                {group.items.map((item) => (
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
        ))}
      </SidebarContent>
      <SidebarFooter>
        <div className="px-2 py-1.5 text-xs text-muted-foreground">
          按 <kbd>D</kbd> 切换深浅色
        </div>
      </SidebarFooter>
    </Sidebar>
  )
}
