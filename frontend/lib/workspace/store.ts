/**
 * Workspace 状态机（真相源）：tab 列表 + 激活态。
 * AI 的 dataweave.ui.open 事件与用户手动操作驱动同一个 store；
 * 后端只存恢复用快照（仅 Ephemeral 与激活态，Pinned 底座不依赖快照）。
 */
import { create } from "zustand"

import { isKnownView, PINNED_VIEWS, type ViewType } from "./views"

export interface WorkspaceTab {
  /** 去重键：view + 规范化 params */
  id: string
  view: ViewType
  params?: Record<string, unknown>
  pinned: boolean
  /** Pinned 底座 tab：恒定存在、不可关闭/unpin、不进快照 */
  base: boolean
}

export interface WorkspaceSnapshot {
  version: 1
  tabs: Array<{ view: string; params?: Record<string, unknown>; pinned: boolean }>
  activeTabId: string | null
}

/** 递归按 key 排序的稳定序列化，保证 params 顺序无关的去重键 */
function stableStringify(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(stableStringify).join(",")}]`
  if (value && typeof value === "object") {
    const entries = Object.entries(value as Record<string, unknown>)
      .filter(([, v]) => v !== undefined)
      .sort(([a], [b]) => (a < b ? -1 : 1))
    return `{${entries.map(([k, v]) => `${JSON.stringify(k)}:${stableStringify(v)}`).join(",")}}`
  }
  return JSON.stringify(value)
}

function normalizeParams(
  params?: Record<string, unknown>,
): Record<string, unknown> | undefined {
  if (!params) return undefined
  const entries = Object.entries(params).filter(([, v]) => v !== undefined && v !== null)
  return entries.length > 0 ? Object.fromEntries(entries) : undefined
}

export function tabKey(view: string, params?: Record<string, unknown>): string {
  const p = normalizeParams(params)
  return p ? `${view}?${stableStringify(p)}` : view
}

function baseTabs(): WorkspaceTab[] {
  return PINNED_VIEWS.map((view) => ({
    id: tabKey(view),
    view,
    pinned: true,
    base: true,
  }))
}

interface WorkspaceState {
  tabs: WorkspaceTab[]
  activeTabId: string
  open: (
    view: string,
    params?: Record<string, unknown>,
    opts?: { activate?: boolean },
  ) => void
  close: (id: string) => void
  activate: (id: string) => void
  pin: (id: string) => void
  unpin: (id: string) => void
  /** 仅 Ephemeral（含 pin 升级者）+ 激活态进快照 */
  snapshot: () => WorkspaceSnapshot
  /** 恢复快照：损坏/未知视图静默丢弃，至少回到 Pinned 底座 */
  restore: (raw: unknown) => void
  reset: () => void
}

export const useWorkspaceStore = create<WorkspaceState>()((set, get) => ({
  tabs: baseTabs(),
  activeTabId: baseTabs()[0]?.id ?? "",

  open: (view, params, opts) => {
    if (!isKnownView(view)) {
      console.warn(`[workspace] 忽略未注册视图: ${view}`)
      return
    }
    const activate = opts?.activate !== false
    const id = tabKey(view, params)
    const { tabs } = get()
    const existing = tabs.find((t) => t.id === id)
    if (existing) {
      if (activate) set({ activeTabId: id })
      return
    }
    const tab: WorkspaceTab = {
      id,
      view,
      params: normalizeParams(params),
      pinned: false,
      base: false,
    }
    set({
      tabs: [...tabs, tab],
      ...(activate ? { activeTabId: id } : {}),
    })
  },

  close: (id) => {
    const { tabs, activeTabId } = get()
    const idx = tabs.findIndex((t) => t.id === id)
    if (idx < 0 || tabs[idx].pinned) return
    const next = tabs.filter((t) => t.id !== id)
    set({
      tabs: next,
      activeTabId:
        activeTabId === id
          ? (next[Math.min(idx, next.length - 1)]?.id ?? "")
          : activeTabId,
    })
  },

  activate: (id) => {
    if (get().tabs.some((t) => t.id === id)) set({ activeTabId: id })
  },

  pin: (id) => {
    set({
      tabs: get().tabs.map((t) => (t.id === id ? { ...t, pinned: true } : t)),
    })
  },

  unpin: (id) => {
    set({
      tabs: get().tabs.map((t) =>
        t.id === id && !t.base ? { ...t, pinned: false } : t,
      ),
    })
  },

  snapshot: () => {
    const { tabs, activeTabId } = get()
    return {
      version: 1,
      tabs: tabs
        .filter((t) => !t.base)
        .map((t) => ({ view: t.view, params: t.params, pinned: t.pinned })),
      activeTabId,
    }
  },

  restore: (raw) => {
    const base = baseTabs()
    const fallback = { tabs: base, activeTabId: base[0]?.id ?? "" }
    try {
      const snap = typeof raw === "string" ? JSON.parse(raw) : raw
      if (!snap || typeof snap !== "object" || !Array.isArray(snap.tabs)) {
        set(fallback)
        return
      }
      const seen = new Set(base.map((t) => t.id))
      const restored: WorkspaceTab[] = [...base]
      for (const t of snap.tabs) {
        if (!t || typeof t.view !== "string" || !isKnownView(t.view)) continue
        const params = normalizeParams(
          t.params && typeof t.params === "object" ? t.params : undefined,
        )
        const id = tabKey(t.view, params)
        if (seen.has(id)) continue
        seen.add(id)
        restored.push({ id, view: t.view, params, pinned: t.pinned === true, base: false })
      }
      const activeTabId =
        typeof snap.activeTabId === "string" && seen.has(snap.activeTabId)
          ? snap.activeTabId
          : (base[0]?.id ?? "")
      set({ tabs: restored, activeTabId })
    } catch {
      set(fallback)
    }
  },

  reset: () => {
    const base = baseTabs()
    set({ tabs: base, activeTabId: base[0]?.id ?? "" })
  },
}))
