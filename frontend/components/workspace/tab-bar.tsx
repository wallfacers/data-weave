"use client"

import { useEffect, useRef, useState } from "react"
import { HugeiconsIcon } from "@hugeicons/react"
import { Cancel01Icon, PinIcon, PinOffIcon, PlusSignIcon } from "@hugeicons/core-free-icons"

import { useWorkspaceStore, type WorkspaceTab } from "@/lib/workspace/store"
import { VIEW_RENDER } from "@/lib/workspace/registry"
import { VIEW_META, type ViewType } from "@/lib/workspace/views"
import { cn } from "@/lib/utils"

/** tab 标签：标题 + params 首值提示（区分同视图不同对象的多个 tab） */
function tabLabel(tab: WorkspaceTab): string {
  const title = VIEW_META[tab.view].title
  if (!tab.params) return title
  const first = Object.values(tab.params)[0]
  return first != null ? `${title} · ${String(first)}` : title
}

function TabItem({ tab, active }: { tab: WorkspaceTab; active: boolean }) {
  const activate = useWorkspaceStore((s) => s.activate)
  const close = useWorkspaceStore((s) => s.close)
  const pin = useWorkspaceStore((s) => s.pin)
  const unpin = useWorkspaceStore((s) => s.unpin)

  return (
    <div
      className={cn(
        "group flex h-8 shrink-0 items-center gap-0.5 rounded-lg pl-2.5 pr-1.5 text-sm transition-colors",
        active
          ? "bg-muted text-foreground"
          : "text-muted-foreground hover:bg-muted/60 hover:text-foreground",
      )}
    >
      <button
        type="button"
        onClick={() => activate(tab.id)}
        className="flex items-center gap-1.5"
      >
        <HugeiconsIcon icon={VIEW_RENDER[tab.view].icon} className="size-3.5 shrink-0" />
        <span className="max-w-40 truncate">{tabLabel(tab)}</span>
      </button>

      {/* pin/unpin：仅非底座 tab 在激活态可见 */}
      {!tab.base && active && (
        <button
          type="button"
          onClick={() => (tab.pinned ? unpin(tab.id) : pin(tab.id))}
          className="flex size-5 items-center justify-center rounded-md text-muted-foreground hover:bg-background hover:text-foreground"
          title={tab.pinned ? "取消固定" : "固定"}
          aria-label={tab.pinned ? "取消固定" : "固定"}
        >
          <HugeiconsIcon icon={tab.pinned ? PinOffIcon : PinIcon} className="size-3" />
        </button>
      )}

      {/* 关闭：仅 Ephemeral（未固定）tab */}
      {!tab.pinned && (
        <button
          type="button"
          onClick={() => close(tab.id)}
          className={cn(
            "flex size-5 items-center justify-center rounded-md text-muted-foreground hover:bg-background hover:text-foreground",
            active ? "" : "opacity-0 group-hover:opacity-100",
          )}
          aria-label={`关闭 ${tabLabel(tab)}`}
        >
          <HugeiconsIcon icon={Cancel01Icon} className="size-3" />
        </button>
      )}
    </div>
  )
}

/** "+" 启动菜单：注册表全部视图，手动打开（不经 AI 的逃生舱） */
function Launcher() {
  const [menuOpen, setMenuOpen] = useState(false)
  const open = useWorkspaceStore((s) => s.open)
  const btnRef = useRef<HTMLButtonElement>(null)
  const [anchor, setAnchor] = useState({ top: 0, left: 0 })

  // 每次展开时抓取按钮视口坐标，下拉菜单用 fixed 定位——
    // 逃离 TabBar 的 overflow-x-auto 裁切（否则下拉被裁成一条缝看不见）。
  useEffect(() => {
    if (!menuOpen || !btnRef.current) return
    const rect = btnRef.current.getBoundingClientRect()
    setAnchor({ top: rect.bottom + 4, left: rect.left })
  }, [menuOpen])

  return (
    <div className="relative shrink-0">
      <button
        ref={btnRef}
        type="button"
        onClick={() => setMenuOpen((v) => !v)}
        className="flex size-8 items-center justify-center rounded-lg text-muted-foreground transition-colors hover:bg-muted/60 hover:text-foreground"
        aria-label="打开视图"
        aria-expanded={menuOpen}
      >
        <HugeiconsIcon icon={PlusSignIcon} className="size-4" />
      </button>
      {menuOpen && (
        <>
          <div
            className="fixed inset-0 z-40"
            onClick={() => setMenuOpen(false)}
            aria-hidden
          />
          <div
            className="fixed z-50 flex w-44 flex-col gap-0.5 rounded-lg border bg-popover p-1 shadow-md"
            style={{ top: anchor.top, left: anchor.left }}
          >
            {(Object.keys(VIEW_META) as ViewType[]).map((view) => (
              <button
                key={view}
                type="button"
                onClick={() => {
                  open(view)
                  setMenuOpen(false)
                }}
                className="flex items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm text-popover-foreground hover:bg-muted"
              >
                <HugeiconsIcon
                  icon={VIEW_RENDER[view].icon}
                  className="size-4 text-muted-foreground"
                />
                {VIEW_META[view].title}
              </button>
            ))}
          </div>
        </>
      )}
    </div>
  )
}

export function WorkspaceTabBar() {
  const tabs = useWorkspaceStore((s) => s.tabs)
  const activeTabId = useWorkspaceStore((s) => s.activeTabId)

  return (
    <div className="flex h-12 shrink-0 items-center gap-1 overflow-x-auto px-3">
      {tabs.map((tab) => (
        <TabItem key={tab.id} tab={tab} active={tab.id === activeTabId} />
      ))}
      <Launcher />
    </div>
  )
}
