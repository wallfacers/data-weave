"use client"

import { Fragment, useEffect, useState, type ReactNode } from "react"
import { HugeiconsIcon, type IconSvgElement } from "@hugeicons/react"
import { Cancel01Icon } from "@hugeicons/core-free-icons"

import { cn } from "@/lib/utils"

/**
 * Chrome 卡片风格标签条（项目统一 Tab）。
 *
 * - 卡片分割 + 顶部弧度：激活态浮起为 bg-background 卡片，与下方内容连体；非激活态相邻处显竖分隔线。
 * - 自动压缩、**无滚动条**：标签 `flex 0 1 220px` + `min-w`，标签多了像 Chrome 一样不断压缩、不出横向滚动。
 * - 右键菜单：关闭 / 关闭其他 / 关闭右侧 / 关闭左侧 / 关闭全部（+ 调用方注入的额外项，如固定/取消固定）。
 *
 * 纯展示组件：数据与动作由各 store 注入，三处（工作区 / 日志面板 / 侧面板）共用。
 */
export interface TabStripItem {
  id: string
  label: string
  icon?: IconSvgElement
  /** 标签最前的状态指示器（如日志面板连接状态圆点），置于 icon/label 之前 */
  indicator?: ReactNode
  /** 默认 true；false = 不可关闭（如工作区固定底座），无 × 且 close 系列跳过 */
  closable?: boolean
  /** 标签文字用等宽字体（如日志面板实例 ID） */
  monospace?: boolean
}

export interface TabContextAction {
  label: string
  onClick: () => void
  disabled?: boolean
}

interface TabStripProps {
  tabs: TabStripItem[]
  activeId: string | null
  onActivate: (id: string) => void
  onClose: (id: string) => void
  onCloseOthers?: (id: string) => void
  onCloseRight?: (id: string) => void
  onCloseLeft?: (id: string) => void
  onCloseAll?: () => void
  /** 每个 tab 额外的右键项（置于关闭项之前），如固定/取消固定 */
  extraActions?: (tab: TabStripItem) => TabContextAction[]
  /** 尾部插槽（如 "+" 启动按钮） */
  trailing?: ReactNode
  size?: "md" | "sm"
  /** 内容面色：激活标签与底角弧度填充此色，与下方内容连体（三处内容面均为 sidebar） */
  surface?: "sidebar" | "background" | "card"
  className?: string
}

const SURFACE: Record<NonNullable<TabStripProps["surface"]>, string> = {
  sidebar: "var(--sidebar)",
  background: "var(--background)",
  card: "var(--card)",
}

interface MenuState {
  tab: TabStripItem
  x: number
  y: number
}

const SIZE = {
  md: { pad: "px-1.5 pt-1.5", tab: "h-9 gap-1.5 pl-3 pr-2 text-sm", radius: "rounded-t-[10px]", icon: "size-3.5", close: "size-5", closeIcon: "size-3" },
  sm: { pad: "px-1 pt-1", tab: "h-8 gap-1 pl-2.5 pr-1.5 text-xs", radius: "rounded-t-lg", icon: "size-3", close: "size-4", closeIcon: "size-2.5" },
} as const

export function TabStrip({
  tabs,
  activeId,
  onActivate,
  onClose,
  onCloseOthers,
  onCloseRight,
  onCloseLeft,
  onCloseAll,
  extraActions,
  trailing,
  size = "md",
  surface = "sidebar",
  className,
}: TabStripProps) {
  const [hoveredId, setHoveredId] = useState<string | null>(null)
  const [menu, setMenu] = useState<MenuState | null>(null)
  const s = SIZE[size]
  const surfaceColor = SURFACE[surface]

  // 关闭右键菜单：点击空白 / Escape / 滚动
  useEffect(() => {
    if (!menu) return
    const close = () => setMenu(null)
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && setMenu(null)
    window.addEventListener("click", close)
    window.addEventListener("resize", close)
    window.addEventListener("keydown", onKey)
    return () => {
      window.removeEventListener("click", close)
      window.removeEventListener("resize", close)
      window.removeEventListener("keydown", onKey)
    }
  }, [menu])

  /** tab[i-1] 与 tab[i] 之间的竖分隔线：任一为激活/悬浮则隐藏（Chrome 行为） */
  const sepHidden = (i: number) => {
    const prev = tabs[i - 1]?.id
    const cur = tabs[i]?.id
    return (
      prev === activeId ||
      cur === activeId ||
      prev === hoveredId ||
      cur === hoveredId
    )
  }

  const closableCount = tabs.filter((t) => t.closable !== false).length

  const buildMenu = (tab: TabStripItem): TabContextAction[] => {
    const idx = tabs.findIndex((t) => t.id === tab.id)
    const closableRight = tabs.slice(idx + 1).some((t) => t.closable !== false)
    const closableLeft = tabs.slice(0, idx).some((t) => t.closable !== false)
    const closableOthers = tabs.some((t) => t.id !== tab.id && t.closable !== false)
    const items: TabContextAction[] = [...(extraActions?.(tab) ?? [])]
    if (tab.closable !== false) {
      items.push({ label: "关闭标签页", onClick: () => onClose(tab.id) })
    }
    if (onCloseOthers) {
      items.push({ label: "关闭其他标签页", onClick: () => onCloseOthers(tab.id), disabled: !closableOthers })
    }
    if (onCloseRight) {
      items.push({ label: "关闭右侧标签页", onClick: () => onCloseRight(tab.id), disabled: !closableRight })
    }
    if (onCloseLeft) {
      items.push({ label: "关闭左侧标签页", onClick: () => onCloseLeft(tab.id), disabled: !closableLeft })
    }
    if (onCloseAll) {
      items.push({ label: "关闭全部标签页", onClick: () => onCloseAll(), disabled: closableCount === 0 })
    }
    return items
  }

  const openMenu = (e: React.MouseEvent, tab: TabStripItem) => {
    e.preventDefault()
    e.stopPropagation()
    setMenu({ tab, x: e.clientX, y: e.clientY })
  }

  return (
    <div className={cn("flex items-end bg-foreground/[0.04]", className)}>
      <div className={cn("flex min-w-0 flex-1 items-end overflow-hidden", s.pad)}>
        {tabs.map((tab, i) => {
          const active = tab.id === activeId
          const closable = tab.closable !== false
          return (
            <Fragment key={tab.id}>
              {/* 竖分隔线 */}
              {i > 0 && (
                <span
                  aria-hidden
                  className={cn(
                    "my-1.5 w-px shrink-0 self-stretch bg-border transition-opacity",
                    sepHidden(i) ? "opacity-0" : "opacity-100",
                  )}
                />
              )}
              <div
                role="tab"
                aria-selected={active}
                tabIndex={0}
                onClick={() => onActivate(tab.id)}
                onContextMenu={(e) => openMenu(e, tab)}
                onMouseEnter={() => setHoveredId(tab.id)}
                onMouseLeave={() => setHoveredId((h) => (h === tab.id ? null : h))}
                onKeyDown={(e) => {
                  if (e.key === "Enter" || e.key === " ") {
                    e.preventDefault()
                    onActivate(tab.id)
                  }
                }}
                style={
                  active
                    ? ({ flex: "0 1 220px", background: surfaceColor, "--dw-tab-surface": surfaceColor } as React.CSSProperties)
                    : { flex: "0 1 220px" }
                }
                className={cn(
                  "group/tab relative flex min-w-10 cursor-pointer select-none items-center transition-colors",
                  s.tab,
                  s.radius,
                  active
                    ? "dw-tab-active font-medium text-foreground"
                    : "text-muted-foreground hover:bg-foreground/[0.04] hover:text-foreground",
                )}
              >
                {tab.indicator && (
                  <span className="flex shrink-0 items-center">{tab.indicator}</span>
                )}
                {tab.icon && (
                  <HugeiconsIcon icon={tab.icon} className={cn("shrink-0", s.icon)} />
                )}
                <span className={cn("min-w-0 flex-1 truncate", tab.monospace && "font-mono")}>
                  {tab.label}
                </span>
                {closable ? (
                  <button
                    type="button"
                    onClick={(e) => {
                      e.stopPropagation()
                      onClose(tab.id)
                    }}
                    aria-label={`关闭 ${tab.label}`}
                    className={cn(
                      "flex shrink-0 items-center justify-center rounded-md text-muted-foreground transition hover:bg-muted hover:text-foreground",
                      s.close,
                      active ? "" : "opacity-0 group-hover/tab:opacity-100",
                    )}
                  >
                    <HugeiconsIcon icon={Cancel01Icon} className={s.closeIcon} />
                  </button>
                ) : (
                  <span className={cn("shrink-0", s.close)} aria-hidden />
                )}
              </div>
            </Fragment>
          )
        })}
      </div>
      {trailing && <div className="flex shrink-0 items-center self-center pr-1.5">{trailing}</div>}

      {/* 右键上下文菜单（fixed 定位，逃离 overflow 裁切） */}
      {menu && (
        <div
          className="fixed z-50 min-w-44 overflow-hidden rounded-lg border bg-popover p-1 shadow-md"
          style={{ top: menu.y, left: menu.x }}
          onClick={(e) => e.stopPropagation()}
          onContextMenu={(e) => e.preventDefault()}
        >
          {buildMenu(menu.tab).map((item, i) => (
            <button
              key={i}
              type="button"
              disabled={item.disabled}
              onClick={() => {
                item.onClick()
                setMenu(null)
              }}
              className={cn(
                "flex w-full items-center rounded-md px-2 py-1.5 text-left text-sm text-popover-foreground transition-colors",
                item.disabled
                  ? "cursor-not-allowed opacity-40"
                  : "hover:bg-muted",
              )}
            >
              {item.label}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
