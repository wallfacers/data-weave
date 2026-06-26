import { beforeEach, describe, expect, it, vi } from "vitest"

import { tabKey, useWorkspaceStore } from "./store"
import { PINNED_VIEWS } from "./views"

const store = () => useWorkspaceStore.getState()

beforeEach(() => {
  store().reset()
})

describe("Pinned 底座", () => {
  it("初始即四个 Pinned tab 且激活第一个", () => {
    expect(store().tabs.map((t) => t.view)).toEqual(PINNED_VIEWS)
    expect(store().tabs.every((t) => t.pinned && t.base)).toBe(true)
    expect(store().activeTabId).toBe(store().tabs[0].id)
  })

  it("Pinned tab 不可关闭、不可 unpin", () => {
    const id = store().tabs[0].id
    store().close(id)
    store().unpin(id)
    expect(store().tabs.find((t) => t.id === id)?.pinned).toBe(true)
    expect(store().tabs).toHaveLength(PINNED_VIEWS.length)
  })
})

describe("open 去重激活", () => {
  it("打开新 Ephemeral tab 并激活", () => {
    store().open("instance-log", { instanceId: 17 })
    const tab = store().tabs.find((t) => t.view === "instance-log")
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
    expect(tabKey("instance-log", { a: 1, b: 2 })).toBe(tabKey("instance-log", { b: 2, a: 1 }))
    store().open("instance-log", { a: 1, b: 2 })
    store().open("instance-log", { b: 2, a: 1 })
    expect(store().tabs.filter((t) => t.view === "instance-log")).toHaveLength(1)
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
    store().open("instance-log", { instanceId: 1 })
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
  it("快照只含 Ephemeral（含 pin 升级者）与激活态", () => {
    store().open("fleet")
    store().open("instance-log", { instanceId: 9 })
    store().pin(tabKey("fleet"))
    const snap = store().snapshot()
    expect(snap.tabs.map((t) => t.view).sort()).toEqual(["instance-log", "fleet"])
    expect(snap.tabs.find((t) => t.view === "fleet")!.pinned).toBe(true)
    expect(snap.activeTabId).toBe(tabKey("instance-log", { instanceId: 9 }))
  })

  it("restore 恢复 Ephemeral 与激活态，Pinned 底座始终在位", () => {
    store().open("fleet")
    store().open("instance-log", { instanceId: 9 })
    const snap = JSON.stringify(store().snapshot())
    store().reset()
    store().restore(snap)
    expect(store().tabs.map((t) => t.view)).toEqual([...PINNED_VIEWS, "fleet", "instance-log"])
    expect(store().activeTabId).toBe(tabKey("instance-log", { instanceId: 9 }))
  })

  it("损坏快照回退纯 Pinned 底座", () => {
    store().open("fleet")
    store().restore("{not json")
    expect(store().tabs.map((t) => t.view)).toEqual(PINNED_VIEWS)
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
    expect(store().tabs.map((t) => t.view)).toEqual([...PINNED_VIEWS, "fleet"])
    expect(store().activeTabId).toBe("fleet")
  })

  it("activeTabId 指向不存在的 tab 时回退首个 Pinned", () => {
    store().restore(JSON.stringify({ version: 1, tabs: [], activeTabId: "ghost" }))
    expect(store().activeTabId).toBe(store().tabs[0].id)
  })
})
