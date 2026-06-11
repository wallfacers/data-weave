"use client"

import { useEffect, useRef } from "react"
import { useRouter, useSearchParams } from "next/navigation"

import { useWorkspaceStore } from "@/lib/workspace/store"
import { useWorkspacePersistence } from "@/lib/workspace/persistence"
import { VIEW_RENDER } from "@/lib/workspace/registry"
import { cn } from "@/lib/utils"
import { WorkspaceTabBar } from "./tab-bar"

/**
 * Workspace 主区：tab 条 + 视图渲染区。
 * 视图惰性挂载、激活过的保持挂载（hidden 切换），保留各 tab 内部状态
 * （如 SQL 编辑器内容）；关闭 tab 即卸载。
 */
export function Workspace() {
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

  return (
    <div className="flex min-h-0 min-w-0 flex-1 flex-col">
      <WorkspaceTabBar />
      {/* 内容区做成与左侧 Agent 面板同款的浮起卡（bg-sidebar + border + shadow-lg + rounded-lg），
          让激活 tab 下方的视图读成「这张卡」，并与左栏对话面板成同材质的一对。 */}
      <div className="relative mx-3 mb-3 flex min-h-0 min-w-0 flex-1 flex-col overflow-hidden rounded-[var(--radius-lg)] border bg-sidebar shadow-lg">
        {tabs
          .filter((t) => mountedRef.current.has(t.id))
          .map((tab) => {
            const View = VIEW_RENDER[tab.view].component
            return (
              <div
                key={tab.id}
                className={cn(
                  "min-h-0 min-w-0 flex-1 flex-col overflow-hidden",
                  tab.id === activeTabId ? "flex" : "hidden",
                )}
              >
                <View params={tab.params} />
              </div>
            )
          })}
      </div>
    </div>
  )
}
