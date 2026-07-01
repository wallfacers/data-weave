import { describe, it, expect, beforeEach, afterEach, vi } from "vitest"

import { useNavUiStore } from "./nav-ui-store"

// vitest 默认 node 环境无 window/localStorage —— store 的持久化以 `typeof window` 守卫；
// stub 出 truthy window + 内存版 localStorage 才能驱动持久化路径。
function fakeLocalStorage() {
  const m = new Map<string, string>()
  return {
    getItem: (k: string) => (m.has(k) ? (m.get(k) as string) : null),
    setItem: (k: string, v: string) => void m.set(k, String(v)),
    removeItem: (k: string) => void m.delete(k),
    clear: () => m.clear(),
  }
}

describe("nav-ui-store 收起/展开（FR-009）", () => {
  let ls: ReturnType<typeof fakeLocalStorage>
  beforeEach(() => {
    ls = fakeLocalStorage()
    vi.stubGlobal("localStorage", ls)
    vi.stubGlobal("window", { localStorage: ls })
    useNavUiStore.setState({ collapsed: false })
  })
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it("toggleCollapsed 切换并持久化", () => {
    useNavUiStore.getState().toggleCollapsed()
    expect(useNavUiStore.getState().collapsed).toBe(true)
    expect(ls.getItem("dw.nav.collapsed")).toBe("1")
    useNavUiStore.getState().toggleCollapsed()
    expect(useNavUiStore.getState().collapsed).toBe(false)
    expect(ls.getItem("dw.nav.collapsed")).toBe("0")
  })

  it("setCollapsed 写入持久化", () => {
    useNavUiStore.getState().setCollapsed(true)
    expect(ls.getItem("dw.nav.collapsed")).toBe("1")
  })
})
