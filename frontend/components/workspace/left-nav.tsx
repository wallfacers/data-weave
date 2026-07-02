"use client"

/**
 * 企业项目左侧导航：顶部项目切换器 + 按功能模块分目录的功能项 + 底部用户菜单。
 *
 * - 功能项点击经 useWorkspaceStore.open() 打开/激活标签页（与「+」菜单、深链一致）。
 * - 高亮当前激活功能（含上下文详情视图归父模块，FR-007）。
 * - 收起为整体 off-canvas 隐藏（宽度归零）；重新展开的入口在 WorkspaceTabBar 的 leading 插槽。
 */
import { useEffect, useState } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  ArrowDown01Icon,
  Building03Icon,
  Logout03Icon,
  MoreVerticalIcon,
  Settings01Icon,
  SidebarLeft01Icon,
  Tick02Icon,
} from "@hugeicons/core-free-icons"

import { cn } from "@/lib/utils"
import { useWorkspaceStore } from "@/lib/workspace/store"
import { VIEW_RENDER } from "@/lib/workspace/registry"
import { VIEW_META, type ViewType } from "@/lib/workspace/views"
import { NAV_GROUPS, resolveActiveHighlight, viewRequiredPermission } from "@/lib/workspace/nav-groups"
import { useProjectContext } from "@/lib/project-context"
import { syncProjectPermissions, useProjectPermissions } from "@/lib/project-permissions"
import { useNavUiStore } from "@/lib/nav-ui-store"
import { useAuth } from "@/lib/auth"
import { SettingsDialog } from "@/components/settings-dialog"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { DwScroll } from "@/components/ui/dw-scroll"

/** 顶部企业项目切换器：展示当前项目 + 下拉切换（空/单项/错误态降级）。 */
function ProjectSwitcher() {
  const t = useTranslations("leftNav")
  const projects = useProjectContext((s) => s.projects)
  const status = useProjectContext((s) => s.status)
  const currentProjectId = useProjectContext((s) => s.currentProjectId)
  const setProject = useProjectContext((s) => s.setProject)
  const loadProjects = useProjectContext((s) => s.loadProjects)
  const [open, setOpen] = useState(false)

  useEffect(() => {
    void loadProjects()
  }, [loadProjects])

  const current = projects.find((p) => p.id === currentProjectId)
  const canSwitch = status === "ready" && projects.length > 1
  const name =
    current?.name ??
    (status === "loading"
      ? t("switcher.loading")
      : status === "error"
        ? t("switcher.error")
        : t("switcher.empty"))

  return (
    <div className="relative min-w-0 flex-1">
      <button
        type="button"
        disabled={!canSwitch}
        onClick={() => canSwitch && setOpen((v) => !v)}
        className={cn(
          "flex w-full items-center gap-2 rounded-md px-2 py-2 text-left transition-colors",
          canSwitch ? "hover:bg-sidebar-accent" : "cursor-default",
        )}
        aria-expanded={open}
        aria-label={t("switcher.label")}
      >
        <HugeiconsIcon icon={Building03Icon} className="size-4 shrink-0 text-muted-foreground" />
        <span className="flex min-w-0 flex-1 flex-col">
          <span className="truncate text-[10px] uppercase tracking-wide text-muted-foreground">
            {t("switcher.label")}
          </span>
          <span className="truncate text-sm font-medium text-sidebar-foreground">{name}</span>
        </span>
        {canSwitch && (
          <HugeiconsIcon icon={ArrowDown01Icon} className="size-4 shrink-0 text-muted-foreground" />
        )}
      </button>
      {open && canSwitch && (
        <>
          <div className="fixed inset-0 z-40" onClick={() => setOpen(false)} aria-hidden />
          <div className="absolute left-0 right-0 top-full z-50 mt-1 flex max-h-72 flex-col gap-0.5 overflow-y-auto rounded-lg border bg-popover p-1 shadow-md">
            {projects.map((p) => (
              <button
                key={p.id}
                type="button"
                onClick={() => {
                  setProject(p.id)
                  setOpen(false)
                }}
                className="flex items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm text-popover-foreground hover:bg-muted"
              >
                <span className="size-4 shrink-0">
                  {p.id === currentProjectId && (
                    <HugeiconsIcon icon={Tick02Icon} className="size-4 text-foreground" />
                  )}
                </span>
                <span className="truncate">{p.name}</span>
              </button>
            ))}
          </div>
        </>
      )}
    </div>
  )
}

/** 单个功能项（图标 + 名称）。 */
function NavItem({ view, active }: { view: ViewType; active: boolean }) {
  const t = useTranslations()
  const open = useWorkspaceStore((s) => s.open)
  const label = t(VIEW_META[view].title)
  return (
    <button
      type="button"
      onClick={() => open(view)}
      aria-current={active ? "page" : undefined}
      className={cn(
        "flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-sm transition-colors",
        active
          ? "bg-sidebar-accent font-medium text-sidebar-accent-foreground"
          : "text-sidebar-foreground hover:bg-sidebar-accent/60",
      )}
    >
      <HugeiconsIcon
        icon={VIEW_RENDER[view].icon}
        className={cn("size-4 shrink-0", !active && "text-muted-foreground")}
      />
      <span className="truncate">{label}</span>
    </button>
  )
}

/** 底部用户菜单：头像 + 姓名/用户名 + 下拉（外观设置 / 退出登录）。 */
function UserMenu({ onOpenSettings }: { onOpenSettings: () => void }) {
  const t = useTranslations("leftNav")
  const { user, logout } = useAuth()
  if (!user) return null
  const initial = (user.displayName || user.username).slice(0, 1).toUpperCase()

  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        render={
          <button
            type="button"
            className="flex w-full items-center gap-2 rounded-md px-2 py-2 text-left transition-colors hover:bg-sidebar-accent"
          />
        }
      >
        <span className="flex size-8 shrink-0 items-center justify-center rounded-md bg-sidebar-accent text-sm font-medium text-sidebar-accent-foreground">
          {initial}
        </span>
        <span className="flex min-w-0 flex-1 flex-col">
          <span className="truncate text-sm font-medium text-sidebar-foreground">{user.displayName}</span>
          <span className="truncate text-xs text-muted-foreground">{user.username}</span>
        </span>
        <HugeiconsIcon icon={MoreVerticalIcon} className="ml-auto size-4 shrink-0 text-muted-foreground" />
      </DropdownMenuTrigger>
      <DropdownMenuContent side="right" align="end">
        <DropdownMenuLabel>
          <div className="flex flex-col">
            <span className="truncate text-sm font-medium text-foreground">{user.displayName}</span>
            <span className="truncate text-xs text-muted-foreground">{user.username}</span>
          </div>
        </DropdownMenuLabel>
        <DropdownMenuSeparator />
        <DropdownMenuItem onClick={onOpenSettings}>
          <HugeiconsIcon icon={Settings01Icon} className="size-4" />
          {t("userMenu.appearanceSettings")}
        </DropdownMenuItem>
        <DropdownMenuSeparator />
        <DropdownMenuItem variant="destructive" onClick={logout}>
          <HugeiconsIcon icon={Logout03Icon} className="size-4" />
          {t("userMenu.logout")}
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

export function LeftNav() {
  const t = useTranslations("leftNav")
  const collapsed = useNavUiStore((s) => s.collapsed)
  const toggleCollapsed = useNavUiStore((s) => s.toggleCollapsed)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const { can } = useProjectPermissions()

  // 036-D：挂载时订阅切项目重算权限（FR-043）；canView 驱动菜单按当前项目角色过滤（FR-041）。
  useEffect(() => syncProjectPermissions(), [])
  const canView = (view: ViewType) => {
    const req = viewRequiredPermission(view)
    return !req || can(req)
  }

  // 当前激活功能 → 高亮归属
  const activeTabId = useWorkspaceStore((s) => s.activeTabId)
  const tabs = useWorkspaceStore((s) => s.tabs)
  const activeView = tabs.find((tab) => tab.id === activeTabId)?.view
  const highlight = resolveActiveHighlight(activeView)

  return (
    <aside
      className={cn(
        "flex h-svh shrink-0 flex-col overflow-hidden bg-background text-sidebar-foreground transition-[width] duration-200 ease-linear",
        collapsed ? "w-0" : "w-72",
      )}
    >
      <div className="flex h-full w-72 flex-col">
        {/* 顶部：项目切换器 + 收起按钮 */}
        <div className="flex shrink-0 items-center gap-1 px-2 py-2">
          <ProjectSwitcher />
          <button
            type="button"
            onClick={toggleCollapsed}
            title={t("collapse.collapse")}
            aria-label={t("collapse.collapse")}
            className="flex size-7 shrink-0 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-sidebar-accent hover:text-sidebar-foreground"
          >
            <HugeiconsIcon icon={SidebarLeft01Icon} className="size-4" />
          </button>
        </div>

        {/* 中部：分组目录（可纵向滚动）——DwScroll 统一 4px 中性灰浮叠滚动条，禁原生带箭头条 */}
        <nav className="min-h-0 flex-1">
          <DwScroll className="h-full" innerClassName="px-2 py-2">
            {NAV_GROUPS.map((group) => {
              // 036-D：按当前项目权限过滤菜单项；整组无可见项则隐藏分组（FR-041）。
              const items = group.items.filter(canView)
              if (items.length === 0) return null
              return (
                <div key={group.id} className="mb-2 last:mb-0">
                  <div className="px-2 pb-1 text-[10px] font-semibold uppercase tracking-wide text-muted-foreground">
                    {t(group.titleKey)}
                  </div>
                  <div className="flex flex-col gap-0.5">
                    {items.map((view) => (
                      <NavItem key={view} view={view} active={highlight.view === view} />
                    ))}
                  </div>
                </div>
              )
            })}
          </DwScroll>
        </nav>

        {/* 底部：用户菜单 */}
        <div className="shrink-0 px-2 py-2">
          <UserMenu onOpenSettings={() => setSettingsOpen(true)} />
        </div>
      </div>

      <SettingsDialog open={settingsOpen} onOpenChange={setSettingsOpen} />
    </aside>
  )
}
