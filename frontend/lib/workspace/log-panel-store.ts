/**
 * 底部日志面板状态：支持多卡片（每个实例一个 tab）。
 * 由实例表格「日志」按钮或 Agent dataweave.ui.open 事件驱动。
 */
import { create } from "zustand"

export interface LogPanelMeta {
  taskId?: number
  startedAt?: string | null
  finishedAt?: string | null
}

export interface LogTab {
  /** 去重键 = instanceId */
  id: string
  instanceId: string
  meta?: LogPanelMeta
}

export interface LogPanelState {
  tabs: LogTab[]
  activeTabId: string | null
  expanded: boolean
  /** 打开日志 tab（已存在则激活，否则追加） */
  open: (instanceId: string, meta?: LogPanelMeta) => void
  /** 关闭指定 tab（关掉最后一个时收起面板） */
  close: (id: string) => void
  /** 关闭其他 tab */
  closeOthers: (id: string) => void
  /** 关闭 id 右侧的 tab */
  closeRight: (id: string) => void
  /** 关闭 id 左侧的 tab */
  closeLeft: (id: string) => void
  /** 关闭全部 tab（收起面板） */
  closeAll: () => void
  /** 激活指定 tab */
  activate: (id: string) => void
  /** 收起面板（不关 tab） */
  collapse: () => void
  /** 内部：仅保留满足 keep 的 tab */
  keepOnly: (keep: (t: LogTab, idx: number) => boolean) => void
}

export const useLogPanelStore = create<LogPanelState>()((set, get) => ({
  tabs: [],
  activeTabId: null,
  expanded: false,

  open: (instanceId, meta) => {
    const { tabs } = get()
    const existing = tabs.find((t) => t.instanceId === instanceId)
    if (existing) {
      set({ activeTabId: existing.id, expanded: true })
    } else {
      const tab: LogTab = { id: instanceId, instanceId, meta }
      set({ tabs: [...tabs, tab], activeTabId: instanceId, expanded: true })
    }
  },

  close: (id) => {
    const { tabs, activeTabId } = get()
    const idx = tabs.findIndex((t) => t.id === id)
    if (idx < 0) return
    const next = tabs.filter((t) => t.id !== id)
    if (next.length === 0) {
      set({ tabs: [], activeTabId: null, expanded: false })
    } else {
      // 激活相邻 tab
      const newActive =
        activeTabId === id
          ? next[Math.min(idx, next.length - 1)].id
          : activeTabId
      set({ tabs: next, activeTabId: newActive })
    }
  },

  /** 保留满足 keep 的 tab；全空则收起面板，激活态失效则回退末尾 */
  keepOnly: (keep: (t: LogTab, idx: number) => boolean) => {
    const { tabs, activeTabId } = get()
    const next = tabs.filter(keep)
    if (next.length === tabs.length) return
    if (next.length === 0) {
      set({ tabs: [], activeTabId: null, expanded: false })
      return
    }
    const activeTabId2 = next.some((t) => t.id === activeTabId)
      ? activeTabId
      : next[next.length - 1].id
    set({ tabs: next, activeTabId: activeTabId2 })
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

  closeAll: () => set({ tabs: [], activeTabId: null, expanded: false }),

  activate: (id) => set({ activeTabId: id, expanded: true }),

  collapse: () => set({ expanded: false }),
}))
