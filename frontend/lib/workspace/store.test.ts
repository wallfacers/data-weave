import { beforeEach, describe, expect, it, vi } from "vitest"

import { tabKey, useWorkspaceStore } from "./store"
import { DEFAULT_VIEWS } from "./views"

const store = () => useWorkspaceStore.getState()

beforeEach(() => {
  store().reset()
})

describe("默认视图（取代 Pinned 底座）", () => {
  it("初始即 DEFAULT_VIEWS 且激活第一个，均可关闭", () => {
    expect(store().tabs.map((t) => t.view)).toEqual(DEFAULT_VIEWS)
    expect(store().tabs.every((t) => !t.pinned && !t.base)).toBe(true)
    expect(store().activeTabId).toBe(store().tabs[0].id)
  })

  it("默认视图可关闭", () => {
    const id = store().tabs[0].id
    const len = store().tabs.length
    store().close(id)
    expect(store().tabs).toHaveLength(len - 1)
  })
})

describe("open 去重激活", () => {
  it("打开新 Ephemeral tab 并激活", () => {
    store().open("ops", { tab: "instances" })
    const tab = store().tabs.find((t) => t.view === "ops")
    expect(tab).toBeDefined()
    expect(tab!.pinned).toBe(false)
    expect(store().activeTabId).toBe(tab!.id)
  })

  it("同 view+params 重复 open 只激活既有 tab", () => {
    store().open("fleet")
    const count = store().tabs.length
    store().activate(store().tabs[0].id)
    store().open("fleet")
    expect(store().tabs).toHaveLength(count)
    expect(store().activeTabId).toBe(tabKey("fleet"))
  })

  it("params 顺序无关，去重键一致", () => {
    expect(tabKey("ops", { a: 1, b: 2 })).toBe(tabKey("ops", { b: 2, a: 1 }))
    store().open("ops", { a: 1, b: 2 })
    store().open("ops", { b: 2, a: 1 })
    expect(store().tabs.filter((t) => t.view === "ops")).toHaveLength(1)
  })

  it("activate:false 打开但不抢焦点", () => {
    const active = store().activeTabId
    store().open("fleet", undefined, { activate: false })
    expect(store().tabs.some((t) => t.view === "fleet")).toBe(true)
    expect(store().activeTabId).toBe(active)
  })

  it("未注册视图忽略并 console.warn", () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {})
    const count = store().tabs.length
    store().open("not-a-view")
    expect(store().tabs).toHaveLength(count)
    expect(warn).toHaveBeenCalledOnce()
    warn.mockRestore()
  })
})

describe("close / pin / unpin", () => {
  it("关闭激活 tab 后焦点落到相邻 tab", () => {
    store().open("fleet")
    store().open("ops", { tab: "instances" })
    const diagId = store().activeTabId
    store().close(diagId)
    expect(store().tabs.some((t) => t.id === diagId)).toBe(false)
    expect(store().activeTabId).toBe(tabKey("fleet"))
  })

  it("Ephemeral 可 pin 升级后不可关闭，unpin 降级后可关闭", () => {
    store().open("fleet")
    const id = tabKey("fleet")
    store().pin(id)
    store().close(id)
    expect(store().tabs.some((t) => t.id === id)).toBe(true)
    store().unpin(id)
    store().close(id)
    expect(store().tabs.some((t) => t.id === id)).toBe(false)
  })
})

describe("snapshot / restore", () => {
  it("快照含全部 tab 与激活态", () => {
    store().open("fleet")
    store().open("ops", { tab: "instances" })
    store().pin(tabKey("fleet"))
    const snap = store().snapshot()
    expect(snap.tabs.map((t) => t.view)).toEqual([...DEFAULT_VIEWS, "fleet", "ops"])
    expect(snap.tabs.find((t) => t.view === "fleet")!.pinned).toBe(true)
    expect(snap.activeTabId).toBe(tabKey("ops", { tab: "instances" }))
  })

  it("restore 恢复全部 tab 与激活态", () => {
    store().open("fleet")
    store().open("ops", { tab: "instances" })
    const snap = JSON.stringify(store().snapshot())
    store().reset()
    store().restore(snap)
    expect(store().tabs.map((t) => t.view)).toEqual([...DEFAULT_VIEWS, "fleet", "ops"])
    expect(store().activeTabId).toBe(tabKey("ops", { tab: "instances" }))
  })

  it("损坏快照回退 DEFAULT_VIEWS", () => {
    store().open("fleet")
    store().restore("{not json")
    expect(store().tabs.map((t) => t.view)).toEqual(DEFAULT_VIEWS)
    expect(store().activeTabId).toBe(store().tabs[0].id)
  })

  it("快照中未知视图静默丢弃，其余照常恢复", () => {
    store().restore(
      JSON.stringify({
        version: 1,
        tabs: [
          { view: "ghost-view", pinned: false },
          { view: "fleet", pinned: false },
        ],
        activeTabId: "fleet",
      }),
    )
    expect(store().tabs.map((t) => t.view)).toEqual(["fleet"])
    expect(store().activeTabId).toBe("fleet")
  })

  it("activeTabId 指向不存在的 tab 时回退首个默认", () => {
    store().restore(JSON.stringify({ version: 1, tabs: [], activeTabId: "ghost" }))
    expect(store().activeTabId).toBe(tabKey(DEFAULT_VIEWS[0]))
  })

  // 042 产品面收缩：含已移除视图（reports/marketplace 等）的旧快照降级。
  // US1 收缩 ViewType 后这些值落入 isKnownView=false 分支被静默丢弃，
  // 用例随之转绿；收缩落地前为红属预期（等待 US1）。
  it("restore 含被删视图(reports)的标签 → 该标签静默丢弃，其余照常恢复", () => {
    store().restore(
      JSON.stringify({
        version: 1,
        tabs: [
          { view: "reports", pinned: false },
          { view: "fleet", pinned: false },
        ],
        activeTabId: tabKey("fleet"),
      }),
    )
    expect(store().tabs.map((t) => t.view)).toEqual(["fleet"])
    expect(store().tabs.some((t) => (t.view as string) === "reports")).toBe(false)
    expect(store().activeTabId).toBe(tabKey("fleet"))
  })

  it("被丢弃者恰为激活标签 → 激活态回退到剩余第一个 tab", () => {
    store().restore(
      JSON.stringify({
        version: 1,
        tabs: [
          { view: "reports", pinned: false },
          { view: "fleet", pinned: false },
        ],
        activeTabId: tabKey("reports"),
      }),
    )
    expect(store().tabs.some((t) => (t.view as string) === "reports")).toBe(false)
    expect(store().tabs.some((t) => t.view === "fleet")).toBe(true)
    // snap.activeTabId 指向被丢弃的 reports → 不在 seen → 回退 restored[0]
    expect(store().activeTabId).toBe(tabKey("fleet"))
  })

  it("快照全部标签均为被删视图 → 回到 DEFAULT_VIEWS", () => {
    store().restore(
      JSON.stringify({
        version: 1,
        tabs: [
          { view: "reports", pinned: false },
          { view: "marketplace", pinned: false },
        ],
        activeTabId: tabKey("reports"),
      }),
    )
    expect(store().tabs.map((t) => t.view)).toEqual(DEFAULT_VIEWS)
    expect(store().activeTabId).toBe(store().tabs[0].id)
  })
})

describe("moveTab", () => {
  it("将 tab 从 fromIdx 移到 toIdx", () => {
    store().open("fleet")
    store().open("ops")
    // 顺序: ...DEFAULT_VIEWS, fleet, ops
    expect(store().tabs.map((t) => t.view)).toEqual([...DEFAULT_VIEWS, "fleet", "ops"])
    // 把 fleet 移到 ops 后面
    const fleetIdx = DEFAULT_VIEWS.length
    store().moveTab(fleetIdx, fleetIdx + 1)
    expect(store().tabs.map((t) => t.view)).toEqual([...DEFAULT_VIEWS, "ops", "fleet"])
  })

  it("相同 from/to 不改变顺序", () => {
    store().open("fleet")
    const before = store().tabs.map((t) => t.view)
    store().moveTab(1, 1)
    expect(store().tabs.map((t) => t.view)).toEqual(before)
  })

  it("越界索引不改变 tabs", () => {
    store().open("fleet")
    const before = store().tabs.map((t) => t.view)
    store().moveTab(-1, 2)
    store().moveTab(0, 999)
    expect(store().tabs.map((t) => t.view)).toEqual(before)
  })
})
