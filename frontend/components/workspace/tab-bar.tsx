"use client"

import { useRef, useState } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import { PlusSignIcon, SidebarLeft01Icon } from "@hugeicons/core-free-icons"

import { useWorkspaceStore, type WorkspaceTab } from "@/lib/workspace/store"
import { VIEW_RENDER } from "@/lib/workspace/registry"
import { VIEW_META, type ViewType } from "@/lib/workspace/views"
import { CONTEXT_DETAIL_VIEWS } from "@/lib/workspace/nav-groups"
import { TabStrip, type TabStripItem, type TabContextAction } from "@/components/ui/tab-strip"
import { useNavUiStore } from "@/lib/nav-ui-store"
import { useProjectPermissions } from "@/lib/project-permissions"

/** UUID 取首 8 位 hex 作短 ID，用于 Tab 标题辨识。 */
function shortId(id: string): string {
  const hex = id.replace(/-/g, "")
  return hex.length > 8 ? hex.slice(-8) : hex
}

/** tab 标签：标题 + params 首值提示（区分同视图不同对象的多个 tab）。UUID 取末 8 位缩短。 */
function tabLabel(tab: WorkspaceTab, t: (k: string) => string): string {
  const title = t(VIEW_META[tab.view].title)
  if (!tab.params) return title
  const first = Object.values(tab.params)[0]
  if (first == null) return title
  const value = typeof first === "string" && first.includes("-") ? shortId(String(first)) : String(first)
  return `${title} · ${value}`
}

/** "+" 启动菜单：仅展示入口视图 + 按当前项目权限过滤（FR-041），与左侧导航一致。 */
function Launcher() {
  const [menuOpen, setMenuOpen] = useState(false)
  const open = useWorkspaceStore((s) => s.open)
  const t = useTranslations()
  const btnRef = useRef<HTMLButtonElement>(null)
  const [anchor, setAnchor] = useState({ top: 0, right: 0 })
  const { can } = useProjectPermissions()

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

  // 仅展示入口视图（排除上下文详情视图），且按当前项目权限过滤（与左侧导航一致）。
  const availableViews = (Object.keys(VIEW_META) as ViewType[]).filter((view) => {
    if (CONTEXT_DETAIL_VIEWS.has(view)) return false
    const req = VIEW_META[view]?.requirePermission
    return !req || can(req)
  })

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
            {availableViews.map((view) => (
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
            {availableViews.length === 0 && (
              <span className="px-2 py-1.5 text-xs text-muted-foreground">
                {t("workspace.noAvailableViews")}
              </span>
            )}
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
  const moveTab = useWorkspaceStore((s) => s.moveTab)
  const navCollapsed = useNavUiStore((s) => s.collapsed)
  const setNavCollapsed = useNavUiStore((s) => s.setCollapsed)
  const t = useTranslations()

  // 固定/取消固定进右键菜单
  const extraActions = (item: TabStripItem): TabContextAction[] => {
    const tab = byId.get(item.id)
    if (!tab) return []
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
      onMoveTab={moveTab}
      extraActions={extraActions}
      leading={
        navCollapsed ? (
          <button
            type="button"
            onClick={() => setNavCollapsed(false)}
            title={t("leftNav.collapse.expand")}
            aria-label={t("leftNav.collapse.expand")}
            className="flex size-7 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-background hover:text-foreground"
          >
            <HugeiconsIcon icon={SidebarLeft01Icon} className="size-4" />
          </button>
        ) : undefined
      }
      trailing={<Launcher />}
    />
  )
}
