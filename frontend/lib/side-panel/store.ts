/**
 * Side Panel 状态机：左栏操作面板的 tab 管理。
 * 独立于 workspace store，纯临时态（无持久化、无 pin/base 概念）。
 */
import { create } from "zustand"

import { tabKey } from "@/lib/workspace/store"

export interface SidePanelTab {
  /** 去重键：view + 规范化 params */
  id: string
  view: string
  title: string
  params?: Record<string, unknown>
}

interface SidePanelState {
  tabs: SidePanelTab[]
  activeTabId: string
  open: (view: string, title: string, params?: Record<string, unknown>) => void
  close: (id: string) => void
  closeOthers: (id: string) => void
  closeRight: (id: string) => void
  closeLeft: (id: string) => void
  activate: (id: string) => void
  closeAll: () => void
  /** 内部：仅保留满足 keep 的 tab */
  keepOnly: (keep: (t: SidePanelTab, idx: number) => boolean) => void
}

export const useSidePanelStore = create<SidePanelState>()((set, get) => ({
  tabs: [],
  activeTabId: "",

  open: (view, title, params) => {
    const id = tabKey(view, params)
    const { tabs } = get()
    const existing = tabs.find((t) => t.id === id)
    if (existing) {
      // 已有同名 tab → 激活 + 更新标题
      set({
        activeTabId: id,
        tabs: tabs.map((t) => (t.id === id ? { ...t, title } : t)),
      })
      return
    }
    set({
      tabs: [...tabs, { id, view, title, params }],
      activeTabId: id,
    })
  },

  close: (id) => {
    const { tabs, activeTabId } = get()
    const idx = tabs.findIndex((t) => t.id === id)
    if (idx < 0) return
    const next = tabs.filter((t) => t.id !== id)
    set({
      tabs: next,
      activeTabId:
        activeTabId === id
          ? (next[Math.min(idx, next.length - 1)]?.id ?? "")
          : activeTabId,
    })
  },

  keepOnly: (keep) => {
    const { tabs, activeTabId } = get()
    const next = tabs.filter(keep)
    if (next.length === tabs.length) return
    set({
      tabs: next,
      activeTabId: next.some((t) => t.id === activeTabId)
        ? activeTabId
        : (next[next.length - 1]?.id ?? ""),
    })
  },

  closeOthers: (id) => get().keepOnly((t) => t.id === id),

  closeRight: (id) => {
    const idx = get().tabs.findIndex((t) => t.id === id)
    if (idx < 0) return
    get().keepOnly((_t, i) => i <= idx)
  },

  closeLeft: (id) => {
    const idx = get().tabs.findIndex((t) => t.id === id)
    if (idx < 0) return
    get().keepOnly((_t, i) => i >= idx)
  },

  activate: (id) => {
    if (get().tabs.some((t) => t.id === id)) set({ activeTabId: id })
  },

  closeAll: () => set({ tabs: [], activeTabId: "" }),
}))
