"use client"

import { useEffect, useRef } from "react"
import { useRouter, useSearchParams } from "next/navigation"
import { useTranslations } from "next-intl"

import { useWorkspaceStore } from "@/lib/workspace/store"
import { useWorkspacePersistence } from "@/lib/workspace/persistence"
import { useLogPanelStore } from "@/lib/workspace/log-panel-store"
import { VIEW_RENDER } from "@/lib/workspace/registry"
import { VIEW_META } from "@/lib/workspace/views"
import { useProjectPermissions } from "@/lib/project-permissions"
import { cn } from "@/lib/utils"
import { WorkspaceTabBar } from "./tab-bar"
import { WorkspaceLogPanel } from "./log-panel"
import { PlaceholderView } from "./views/placeholder-view"

/**
 * Workspace 主区：tab 条 + 视图渲染区。
 * 视图惰性挂载、激活过的保持挂载（hidden 切换），保留各 tab 内部状态
 * （如 SQL 编辑器内容）；关闭 tab 即卸载。
 */
export function Workspace() {
  const t = useTranslations("permission")
  const { can } = useProjectPermissions()
  const tabs = useWorkspaceStore((s) => s.tabs)
  const activeTabId = useWorkspaceStore((s) => s.activeTabId)
  const searchParams = useSearchParams()
  const router = useRouter()

  // 快照随会话持久化：挂载恢复 + 变更防抖回写
  useWorkspacePersistence()

  // 深链逃生舱：消费 ?open=<view>（其余 query 作为视图 params），随后清掉
  // query 避免刷新重复打开；非法视图由 store 忽略，自然回落默认布局。
  useEffect(() => {
    const view = searchParams.get("open")
    if (!view) return
    const params: Record<string, unknown> = {}
    searchParams.forEach((value, key) => {
      if (key !== "open") params[key] = value
    })
    useWorkspaceStore
      .getState()
      .open(view, Object.keys(params).length > 0 ? params : undefined)
    router.replace("/", { scroll: false })
  }, [searchParams, router])

  // keep-alive：激活过的 tab 持续挂载；已关闭的剔除。
  const mountedRef = useRef<Set<string>>(new Set())
  mountedRef.current.add(activeTabId)
  const liveIds = new Set(tabs.map((t) => t.id))
  for (const id of mountedRef.current) {
    if (!liveIds.has(id)) mountedRef.current.delete(id)
  }

  const logPanelExpanded = useLogPanelStore((s) => s.expanded)

  return (
    <div className="flex min-h-0 min-w-0 flex-1 flex-col">
      {/* tab 条 + 内容合成同一张浮起卡（bg-sidebar + border + shadow-lg + rounded-lg）：
          Chrome 标签坐在卡片顶部、底角连入下方视图，整模块读成「一张卡」，
          与左栏 Agent 对话面板成同材质的一对。 */}
      <div className={cn(
        "relative mx-3 mt-3 flex min-h-0 min-w-0 flex-1 flex-col overflow-hidden rounded-[var(--radius-lg)] border bg-sidebar shadow-lg",
        !logPanelExpanded && "mb-3",
      )}>
        <WorkspaceTabBar />
        <div className="relative flex min-h-0 min-w-0 flex-1 flex-col overflow-hidden">
          {tabs
            .filter((t) => mountedRef.current.has(t.id))
            .map((tab) => {
              const View = VIEW_RENDER[tab.view].component
              // 036-D：视图级权限拦截——无权限的视图（深链直达）渲染占位，不暴露真实功能（FR-041）。
              const req = VIEW_META[tab.view]?.requirePermission
              const denied = !!req && !can(req)
              return (
                <div
                  key={tab.id}
                  className={cn(
                    "min-h-0 min-w-0 flex-1 flex-col overflow-hidden",
                    tab.id === activeTabId ? "flex" : "hidden",
                  )}
                >
                  {denied ? (
                    <PlaceholderView title={t("deniedTitle")} description={t("deniedDesc")} />
                  ) : (
                    <View params={tab.params} active={tab.id === activeTabId} />
                  )}
                </div>
              )
            })}
        </div>
      </div>

      {/* 底部日志面板（条件渲染，含拖拽分割线） */}
      <WorkspaceLogPanel />
    </div>
  )
}
