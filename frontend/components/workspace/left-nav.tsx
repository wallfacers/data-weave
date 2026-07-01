"use client"

/**
 * 企业项目左侧导航：顶部项目切换器 + 按功能模块分目录的功能项 + 收起/展开。
 *
 * - 功能项点击经 useWorkspaceStore.open() 打开/激活标签页（与「+」菜单、深链一致）。
 * - 高亮当前激活功能（含上下文详情视图归父模块，FR-007）。
 * - 收起为仅图标 icon rail（icon 仍可一键打开）；偏好持久化。
 */
import { useEffect, useRef, useState } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  ArrowDown01Icon,
  Building03Icon,
  SidebarLeft01Icon,
  Tick02Icon,
} from "@hugeicons/core-free-icons"

import { cn } from "@/lib/utils"
import { useWorkspaceStore } from "@/lib/workspace/store"
import { VIEW_RENDER } from "@/lib/workspace/registry"
import { VIEW_META, type ViewType } from "@/lib/workspace/views"
import { NAV_GROUPS, resolveActiveHighlight } from "@/lib/workspace/nav-groups"
import { useProjectContext } from "@/lib/project-context"
import { useNavUiStore } from "@/lib/nav-ui-store"

/** 顶部企业项目切换器：展示当前项目 + 下拉切换（空/单项/错误态降级）。 */
function ProjectSwitcher({ collapsed }: { collapsed: boolean }) {
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

  if (collapsed) {
    return (
      <div
        className="flex items-center justify-center py-1"
        title={`${t("switcher.label")}: ${name}`}
      >
        <span className="flex size-8 items-center justify-center rounded-md bg-sidebar-accent text-sidebar-accent-foreground">
          <HugeiconsIcon icon={Building03Icon} className="size-4" />
        </span>
      </div>
    )
  }

  return (
    <div className="relative">
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

/** 单个功能项（图标 + 名称；收起态仅图标 + title 悬停）。 */
function NavItem({
  view,
  active,
  collapsed,
}: {
  view: ViewType
  active: boolean
  collapsed: boolean
}) {
  const t = useTranslations()
  const open = useWorkspaceStore((s) => s.open)
  const label = t(VIEW_META[view].title)
  return (
    <button
      type="button"
      onClick={() => open(view)}
      title={collapsed ? label : undefined}
      aria-current={active ? "page" : undefined}
      className={cn(
        "flex items-center rounded-md text-sm transition-colors",
        collapsed ? "size-9 justify-center" : "w-full gap-2 px-2 py-1.5",
        active
          ? "bg-sidebar-accent font-medium text-sidebar-accent-foreground"
          : "text-sidebar-foreground hover:bg-sidebar-accent/60",
      )}
    >
      <HugeiconsIcon
        icon={VIEW_RENDER[view].icon}
        className={cn("size-4 shrink-0", !active && "text-muted-foreground")}
      />
      {!collapsed && <span className="truncate">{label}</span>}
    </button>
  )
}

export function LeftNav() {
  const t = useTranslations("leftNav")
  const collapsed = useNavUiStore((s) => s.collapsed)
  const toggleCollapsed = useNavUiStore((s) => s.toggleCollapsed)

  // 当前激活功能 → 高亮归属
  const activeTabId = useWorkspaceStore((s) => s.activeTabId)
  const tabs = useWorkspaceStore((s) => s.tabs)
  const activeView = tabs.find((tab) => tab.id === activeTabId)?.view
  const highlight = resolveActiveHighlight(activeView)

  return (
    <aside className={cn("h-svh shrink-0 py-3 pl-3 pr-1.5", collapsed ? "w-16" : "w-56")}>
      <div className="flex h-full flex-col overflow-hidden rounded-[var(--radius-lg)] border bg-sidebar shadow-lg">
        {/* 顶部：项目切换器 */}
        <div className={cn("shrink-0", collapsed ? "px-1.5 pt-2" : "px-2 pt-2")}>
          <ProjectSwitcher collapsed={collapsed} />
        </div>

        {/* 中部：分组目录（可纵向滚动） */}
        <nav className="min-h-0 flex-1 overflow-y-auto px-1.5 py-2">
          {NAV_GROUPS.map((group) => (
            <div key={group.id} className="mb-2 last:mb-0">
              {!collapsed && (
                <div className="px-2 pb-1 text-[10px] font-semibold uppercase tracking-wide text-muted-foreground">
                  {t(group.titleKey)}
                </div>
              )}
              <div className={cn("flex flex-col gap-0.5", collapsed && "items-center")}>
                {group.items.map((view) => (
                  <NavItem
                    key={view}
                    view={view}
                    active={highlight.view === view}
                    collapsed={collapsed}
                  />
                ))}
              </div>
            </div>
          ))}
        </nav>

        {/* 底部：收起/展开 */}
        <div className={cn("shrink-0 px-1.5 py-2", collapsed && "flex justify-center")}>
          <button
            type="button"
            onClick={toggleCollapsed}
            title={collapsed ? t("collapse.expand") : t("collapse.collapse")}
            aria-label={collapsed ? t("collapse.expand") : t("collapse.collapse")}
            className={cn(
              "flex items-center rounded-md text-muted-foreground transition-colors hover:bg-sidebar-accent hover:text-sidebar-foreground",
              collapsed ? "size-9 justify-center" : "w-full gap-2 px-2 py-1.5 text-sm",
            )}
          >
            <HugeiconsIcon
              icon={SidebarLeft01Icon}
              className={cn("size-4 shrink-0", !collapsed && "rotate-180")}
            />
            {!collapsed && <span className="truncate">{t("collapse.collapse")}</span>}
          </button>
        </div>
      </div>
    </aside>
  )
}
