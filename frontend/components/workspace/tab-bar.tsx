"use client"

import { useRef, useState } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import { PlusSignIcon } from "@hugeicons/core-free-icons"

import { useWorkspaceStore, type WorkspaceTab } from "@/lib/workspace/store"
import { VIEW_RENDER } from "@/lib/workspace/registry"
import { VIEW_META, type ViewType } from "@/lib/workspace/views"
import { TabStrip, type TabStripItem, type TabContextAction } from "@/components/ui/tab-strip"

/** tab 标签：标题 + params 首值提示（区分同视图不同对象的多个 tab） */
function tabLabel(tab: WorkspaceTab, t: (k: string) => string): string {
  const title = t(VIEW_META[tab.view].title)
  if (!tab.params) return title
  const first = Object.values(tab.params)[0]
  return first != null ? `${title} · ${String(first)}` : title
}

/** "+" 启动菜单：注册表全部视图，手动打开（不经 AI 的逃生舱） */
function Launcher() {
  const [menuOpen, setMenuOpen] = useState(false)
  const open = useWorkspaceStore((s) => s.open)
  const t = useTranslations()
  const btnRef = useRef<HTMLButtonElement>(null)
  const [anchor, setAnchor] = useState({ top: 0, right: 0 })

  // 展开前在点击事件里同步抓取按钮视口坐标（不能放 useEffect：绘制后才执行，
  // 首帧菜单会闪现在视口左上角再跳回按钮下方）。下拉菜单用 fixed + 右对齐
  // （按钮在卡片最右，菜单右缘对齐按钮右缘、向左展开，避免溢出浏览器右边界）。
  const toggleMenu = () => {
    if (!menuOpen && btnRef.current) {
      const rect = btnRef.current.getBoundingClientRect()
      setAnchor({ top: rect.bottom + 4, right: window.innerWidth - rect.right })
    }
    setMenuOpen((v) => !v)
  }

  return (
    <div className="relative">
      <button
        ref={btnRef}
        type="button"
        onClick={toggleMenu}
        className="flex size-7 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-background hover:text-foreground"
        aria-label={t("workspace.openView")}
        aria-expanded={menuOpen}
      >
        <HugeiconsIcon icon={PlusSignIcon} className="size-4" />
      </button>
      {menuOpen && (
        <>
          <div className="fixed inset-0 z-40" onClick={() => setMenuOpen(false)} aria-hidden />
          <div
            className="fixed z-50 flex w-44 flex-col gap-0.5 rounded-lg border bg-popover p-1 shadow-md"
            style={{ top: anchor.top, right: anchor.right }}
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
                <HugeiconsIcon icon={VIEW_RENDER[view].icon} className="size-4 text-muted-foreground" />
                {t(VIEW_META[view].title)}
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
  const activate = useWorkspaceStore((s) => s.activate)
  const close = useWorkspaceStore((s) => s.close)
  const closeOthers = useWorkspaceStore((s) => s.closeOthers)
  const closeRight = useWorkspaceStore((s) => s.closeRight)
  const closeLeft = useWorkspaceStore((s) => s.closeLeft)
  const closeAll = useWorkspaceStore((s) => s.closeAll)
  const pin = useWorkspaceStore((s) => s.pin)
  const unpin = useWorkspaceStore((s) => s.unpin)
  const t = useTranslations()

  // 固定/取消固定进右键菜单（底座 base tab 不可固定切换）
  const extraActions = (item: TabStripItem): TabContextAction[] => {
    const tab = byId.get(item.id)
    if (!tab || tab.base) return []
    return tab.pinned
      ? [{ label: t("workspace.unpinTab"), onClick: () => unpin(tab.id) }]
      : [{ label: t("workspace.pinTab"), onClick: () => pin(tab.id) }]
  }

  const byId = new Map(tabs.map((t) => [t.id, t]))
  const items: TabStripItem[] = tabs.map((tab) => ({
    id: tab.id,
    label: tabLabel(tab, t),
    icon: VIEW_RENDER[tab.view].icon,
    closable: !tab.pinned,
  }))

  return (
    <TabStrip
      size="md"
      className="shrink-0"
      tabs={items}
      activeId={activeTabId}
      onActivate={activate}
      onClose={close}
      onCloseOthers={closeOthers}
      onCloseRight={closeRight}
      onCloseLeft={closeLeft}
      onCloseAll={closeAll}
      extraActions={extraActions}
      trailing={<Launcher />}
    />
  )
}
