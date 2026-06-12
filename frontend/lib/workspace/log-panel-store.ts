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
  /** 激活指定 tab */
  activate: (id: string) => void
  /** 收起面板（不关 tab） */
  collapse: () => void
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

  activate: (id) => set({ activeTabId: id, expanded: true }),

  collapse: () => set({ expanded: false }),
}))
