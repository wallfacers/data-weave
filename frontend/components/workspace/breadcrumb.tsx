"use client"

import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import { ArrowRight01Icon } from "@hugeicons/core-free-icons"

import { useWorkspaceStore } from "@/lib/workspace/store"
import { useProjectContext } from "@/lib/project-context"
import { deriveBreadcrumbNodes } from "@/lib/workspace/breadcrumb"
import { cn } from "@/lib/utils"

/**
 * 内容区顶部面包屑：根据当前激活 Tab 的 view/params 派生「项目 > 分组 > 视图(> 动态参数)」路径。
 *
 * 数据源全部复用既有数据（nav-groups / views / project-context），零新增状态。
 * 严格遵守 frontend/DESIGN.md「无分割线」条款：不加 border-b/border-t/<Separator>/<hr>，
 * 区隔靠背景层次（bg-foreground/[0.04]）和 padding（px-4 py-2）。
 */
export function WorkspaceBreadcrumb() {
  const t = useTranslations()
  const tabs = useWorkspaceStore((s) => s.tabs)
  const activeTabId = useWorkspaceStore((s) => s.activeTabId)
  const { currentProjectId, projects } = useProjectContext()

  const activeTab = tabs.find((tab) => tab.id === activeTabId)
  if (!activeTab) return null

  const project = projects.find((p) => p.id === currentProjectId)
  const projectName = project?.name ?? ""

  const nodes = deriveBreadcrumbNodes(
    activeTab.view,
    activeTab.params,
    projectName,
    t,
  )

  return (
    <nav
      aria-label={t("workspace.breadcrumb.ariaLabel")}
      className={cn(
        "shrink-0 overflow-hidden px-4 py-2",
        "bg-foreground/[0.04]",
      )}
    >
      <ol className="flex list-none items-center gap-1">
        {nodes.map((node, i) => (
          <li key={i} className="flex min-w-0 items-center gap-1">
            {i > 0 && (
              <HugeiconsIcon
                icon={ArrowRight01Icon}
                className="size-3 shrink-0 text-muted-foreground/50"
                aria-hidden
              />
            )}
            <span
              className={cn(
                "truncate text-sm",
                i === nodes.length - 1
                  ? "text-foreground"
                  : "text-muted-foreground",
              )}
            >
              {node.label}
            </span>
          </li>
        ))}
      </ol>
    </nav>
  )
}
