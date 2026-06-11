"use client"

import { useRef } from "react"
import { HugeiconsIcon } from "@hugeicons/react"
import { Cancel01Icon } from "@hugeicons/core-free-icons"

import { useSidePanelStore } from "@/lib/side-panel/store"
import { SIDE_PANEL_VIEW_RENDER } from "@/lib/side-panel/registry"
import { DwScroll } from "@/components/ui/dw-scroll"
import { cn } from "@/lib/utils"

/**
 * 左栏操作面板：AgentRail 和主工作区之间，条件渲染。
 * 无 tab 时 return null，不占空间。
 * keep-alive 模式与 workspace.tsx 一致。
 */
export function SidePanel() {
  const tabs = useSidePanelStore((s) => s.tabs)
  const activeTabId = useSidePanelStore((s) => s.activeTabId)
  const activate = useSidePanelStore((s) => s.activate)
  const close = useSidePanelStore((s) => s.close)

  // keep-alive：激活过的 tab 持续挂载
  const mountedRef = useRef<Set<string>>(new Set())
  mountedRef.current.add(activeTabId)
  const liveIds = new Set(tabs.map((t) => t.id))
  for (const id of mountedRef.current) {
    if (!liveIds.has(id)) mountedRef.current.delete(id)
  }

  if (tabs.length === 0) return null

  return (
    <div className="w-[400px] shrink-0 p-3 pl-1.5">
      <div className="flex h-full flex-col overflow-hidden rounded-[var(--radius-lg)] border bg-sidebar shadow-lg">
        {/* Mini Tab Bar */}
        <DwScroll direction="horizontal" className="h-10 shrink-0 px-2" innerClassName="flex items-center gap-0.5">
          {tabs.map((tab) => {
            const render = SIDE_PANEL_VIEW_RENDER[tab.view as keyof typeof SIDE_PANEL_VIEW_RENDER]
            const isActive = tab.id === activeTabId
            return (
              <div
                key={tab.id}
                className={cn(
                  "group flex h-7 shrink-0 items-center gap-1 rounded-md pl-2 pr-1 text-xs transition-colors",
                  isActive
                    ? "bg-muted text-foreground"
                    : "text-muted-foreground hover:bg-muted/60 hover:text-foreground",
                )}
              >
                <button
                  type="button"
                  onClick={() => activate(tab.id)}
                  className="flex items-center gap-1"
                >
                  {render && (
                    <HugeiconsIcon icon={render.icon} className="size-3 shrink-0" />
                  )}
                  <span className="max-w-28 truncate">{tab.title}</span>
                </button>
                <button
                  type="button"
                  onClick={() => close(tab.id)}
                  className="flex size-4 items-center justify-center rounded-sm text-muted-foreground hover:bg-background hover:text-foreground"
                  aria-label={`关闭 ${tab.title}`}
                >
                  <HugeiconsIcon icon={Cancel01Icon} className="size-2.5" />
                </button>
              </div>
            )
          })}
        </DwScroll>

        {/* 内容区：keep-alive */}
        {tabs
          .filter((t) => mountedRef.current.has(t.id))
          .map((tab) => {
            const render = SIDE_PANEL_VIEW_RENDER[tab.view as keyof typeof SIDE_PANEL_VIEW_RENDER]
            if (!render) return null
            const View = render.component
            return (
              <div
                key={tab.id}
                className={cn(
                  "min-h-0 min-w-0 flex-1 flex-col overflow-hidden",
                  tab.id === activeTabId ? "flex" : "hidden",
                )}
              >
                <View params={tab.params} onClose={() => close(tab.id)} />
              </div>
            )
          })}
      </div>
    </div>
  )
}
