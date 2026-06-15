"use client"

import { useRef } from "react"

import { useSidePanelStore } from "@/lib/side-panel/store"
import { SIDE_PANEL_VIEW_RENDER } from "@/lib/side-panel/registry"
import { TabStrip, type TabStripItem } from "@/components/ui/tab-strip"
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
  const closeOthers = useSidePanelStore((s) => s.closeOthers)
  const closeRight = useSidePanelStore((s) => s.closeRight)
  const closeLeft = useSidePanelStore((s) => s.closeLeft)
  const closeAll = useSidePanelStore((s) => s.closeAll)

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
        {/* Mini Tab Bar（Chrome 卡片风格，统一 TabStrip） */}
        <TabStrip
          size="sm"
          className="shrink-0 rounded-t-[var(--radius-lg)]"
          tabs={tabs.map<TabStripItem>((tab) => {
            const render = SIDE_PANEL_VIEW_RENDER[tab.view as keyof typeof SIDE_PANEL_VIEW_RENDER]
            return { id: tab.id, label: tab.title, icon: render?.icon }
          })}
          activeId={activeTabId}
          onActivate={activate}
          onClose={close}
          onCloseOthers={closeOthers}
          onCloseRight={closeRight}
          onCloseLeft={closeLeft}
          onCloseAll={closeAll}
        />

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
