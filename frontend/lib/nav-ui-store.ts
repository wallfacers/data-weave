"use client"

/**
 * 左侧导航 UI 偏好（zustand + localStorage）。
 * collapsed=true 时导航收起为仅图标的窄条（icon rail）。初始值同步从 localStorage 读取，
 * 首屏无闪烁（仿 lib/date-format-store.ts）。
 */
import { create } from "zustand"

const STORAGE_KEY = "dw.nav.collapsed"

interface NavUiState {
  collapsed: boolean
  toggleCollapsed: () => void
  setCollapsed: (v: boolean) => void
}

function readInitialCollapsed(): boolean {
  if (typeof window === "undefined") return false
  try {
    return localStorage.getItem(STORAGE_KEY) === "1"
  } catch {
    return false
  }
}

function persist(collapsed: boolean) {
  if (typeof window === "undefined") return
  try {
    localStorage.setItem(STORAGE_KEY, collapsed ? "1" : "0")
  } catch {
    /* localStorage 不可用 */
  }
}

export const useNavUiStore = create<NavUiState>((set, get) => ({
  collapsed: readInitialCollapsed(),
  toggleCollapsed: () => {
    const next = !get().collapsed
    set({ collapsed: next })
    persist(next)
  },
  setCollapsed: (v) => {
    set({ collapsed: v })
    persist(v)
  },
}))
