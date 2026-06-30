import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import {
  createRefreshScheduler,
  initialLiveDataState,
  liveDataReducer,
} from "./refresh-scheduler"

describe("RefreshScheduler — gating（仅 active && enabled && visible 才轮询）", () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())

  it("非激活 → 不轮询，请求数为 0（FR-003/SC-003）", async () => {
    const tick = vi.fn(() => Promise.resolve())
    const s = createRefreshScheduler(tick, 1000, { active: false, enabled: true, visible: true })
    await vi.advanceTimersByTimeAsync(5000)
    expect(tick).toHaveBeenCalledTimes(0)
    s.dispose()
  })

  it("开关关闭 → 不轮询", async () => {
    const tick = vi.fn(() => Promise.resolve())
    const s = createRefreshScheduler(tick, 1000, { active: true, enabled: false, visible: true })
    await vi.advanceTimersByTimeAsync(5000)
    expect(tick).toHaveBeenCalledTimes(0)
    s.dispose()
  })

  it("窗口不可见 → 不轮询（FR-005）", async () => {
    const tick = vi.fn(() => Promise.resolve())
    const s = createRefreshScheduler(tick, 1000, { active: true, enabled: true, visible: false })
    await vi.advanceTimersByTimeAsync(5000)
    expect(tick).toHaveBeenCalledTimes(0)
    s.dispose()
  })

  it("active && enabled && visible → 按周期轮询", async () => {
    const tick = vi.fn(() => Promise.resolve())
    const s = createRefreshScheduler(tick, 1000, { active: true, enabled: true, visible: true })
    // 进入运行边沿立即一次
    await vi.advanceTimersByTimeAsync(0)
    expect(tick).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(3000)
    expect(tick).toHaveBeenCalledTimes(4)
    s.dispose()
  })
})

describe("RefreshScheduler — 边沿立即刷新（FR-004/FR-005）", () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())

  it("active false→true 立即触发一次", async () => {
    const tick = vi.fn(() => Promise.resolve())
    const s = createRefreshScheduler(tick, 1000, { active: false, enabled: true, visible: true })
    await vi.advanceTimersByTimeAsync(2000)
    expect(tick).toHaveBeenCalledTimes(0)
    s.update({ active: true })
    await vi.advanceTimersByTimeAsync(0)
    expect(tick).toHaveBeenCalledTimes(1)
    s.dispose()
  })

  it("窗口 visible false→true 且 active 时立即触发一次", async () => {
    const tick = vi.fn(() => Promise.resolve())
    const s = createRefreshScheduler(tick, 1000, { active: true, enabled: true, visible: false })
    await vi.advanceTimersByTimeAsync(2000)
    expect(tick).toHaveBeenCalledTimes(0)
    s.update({ visible: true })
    await vi.advanceTimersByTimeAsync(0)
    expect(tick).toHaveBeenCalledTimes(1)
    s.dispose()
  })
})

describe("RefreshScheduler — 去重/不堆叠/合并（FR-008/FR-009/SC-005）", () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())

  it("在途请求未结束时，轮询不堆叠（onTick 只进行中一次）", async () => {
    let resolve!: () => void
    const tick = vi.fn(() => new Promise<void>((r) => (resolve = r)))
    const s = createRefreshScheduler(tick, 1000, { active: true, enabled: true, visible: true })
    await vi.advanceTimersByTimeAsync(0) // 边沿首次
    expect(tick).toHaveBeenCalledTimes(1)
    // 多个周期内首次仍未结束 → 不再发起
    await vi.advanceTimersByTimeAsync(3000)
    expect(tick).toHaveBeenCalledTimes(1)
    // 结束后，下一周期恢复
    resolve()
    await vi.advanceTimersByTimeAsync(1000)
    expect(tick).toHaveBeenCalledTimes(2)
    s.dispose()
  })

  it("手动 tickNow 命中在途 → 复用同一 promise（不并发）", async () => {
    let resolve!: () => void
    const tick = vi.fn(() => new Promise<void>((r) => (resolve = r)))
    const s = createRefreshScheduler(tick, 1000, { active: true, enabled: true, visible: true })
    await vi.advanceTimersByTimeAsync(0)
    const p1 = s.tickNow()
    const p2 = s.tickNow()
    expect(p1).toBe(p2)
    expect(tick).toHaveBeenCalledTimes(1)
    resolve()
    await p1
    s.dispose()
  })

  it("暂停（enabled=false）时手动 tickNow 仍可发起（FR-014②）", async () => {
    const tick = vi.fn(() => Promise.resolve())
    const s = createRefreshScheduler(tick, 1000, { active: true, enabled: false, visible: true })
    await vi.advanceTimersByTimeAsync(2000)
    expect(tick).toHaveBeenCalledTimes(0) // 未自动轮询
    await s.tickNow()
    expect(tick).toHaveBeenCalledTimes(1) // 手动仍可
    s.dispose()
  })

  it("dispose 后不再轮询", async () => {
    const tick = vi.fn(() => Promise.resolve())
    const s = createRefreshScheduler(tick, 1000, { active: true, enabled: true, visible: true })
    await vi.advanceTimersByTimeAsync(0)
    expect(tick).toHaveBeenCalledTimes(1)
    s.dispose()
    await vi.advanceTimersByTimeAsync(5000)
    expect(tick).toHaveBeenCalledTimes(1)
  })
})

describe("liveDataReducer — 无感更新 + 失败保留旧数据（FR-002/FR-010）", () => {
  it("首屏无数据时 start → loading；成功 → 填充数据并记录时间", () => {
    let st = initialLiveDataState<number>()
    st = liveDataReducer(st, { type: "start" })
    expect(st).toMatchObject({ loading: true, refreshing: false, data: null })
    st = liveDataReducer(st, { type: "success", data: 42, at: 1000 })
    expect(st).toMatchObject({ data: 42, loading: false, refreshing: false, error: false, stale: false, lastUpdatedAt: 1000 })
  })

  it("已有数据时 start → refreshing 且保留旧数据（不闪）", () => {
    let st = liveDataReducer(initialLiveDataState<number>(), { type: "success", data: 7, at: 100 })
    st = liveDataReducer(st, { type: "start" })
    expect(st).toMatchObject({ data: 7, loading: false, refreshing: true })
  })

  it("刷新失败 → 保留上次成功数据并置 stale；下次成功清 stale", () => {
    let st = liveDataReducer(initialLiveDataState<number>(), { type: "success", data: 9, at: 100 })
    st = liveDataReducer(st, { type: "start" })
    st = liveDataReducer(st, { type: "error" })
    expect(st).toMatchObject({ data: 9, error: true, stale: true, refreshing: false })
    st = liveDataReducer(st, { type: "success", data: 11, at: 200 })
    expect(st).toMatchObject({ data: 11, error: false, stale: false, lastUpdatedAt: 200 })
  })

  it("从未成功过时失败 → error 但不 stale（无旧数据可展示）", () => {
    let st = liveDataReducer(initialLiveDataState<number>(), { type: "start" })
    st = liveDataReducer(st, { type: "error" })
    expect(st).toMatchObject({ data: null, error: true, stale: false })
  })
})

// ─── T026 确认 + 补充边界用例 ──────────────────────────────

describe("RefreshScheduler — T026 确认：手动合并 / 暂停仍手动 / disposed 安全", () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())

  it("T026-confirm: tickNow 在途复用同一 promise（FR-008/SC-005）", async () => {
    let resolve!: () => void
    const tick = vi.fn(() => new Promise<void>((r) => (resolve = r)))
    const s = createRefreshScheduler(tick, 1000, { active: true, enabled: true, visible: true })
    await vi.advanceTimersByTimeAsync(0)
    expect(tick).toHaveBeenCalledTimes(1)
    const p1 = s.tickNow()
    const p2 = s.tickNow()
    expect(p1).toBe(p2) // 合并
    expect(tick).toHaveBeenCalledTimes(1) // 未并发
    resolve()
    await p1
    s.dispose()
  })

  it("T026-confirm: enabled=false 手动仍可发起（FR-014②）", async () => {
    const tick = vi.fn(() => Promise.resolve())
    const s = createRefreshScheduler(tick, 1000, { active: true, enabled: false, visible: true })
    await vi.advanceTimersByTimeAsync(5000)
    expect(tick).toHaveBeenCalledTimes(0)
    await s.tickNow()
    expect(tick).toHaveBeenCalledTimes(1)
    // 手动后又一轮周期仍不自动触发
    await vi.advanceTimersByTimeAsync(5000)
    expect(tick).toHaveBeenCalledTimes(1)
    s.dispose()
  })

  it("disposed 后 update 不抛错", () => {
    const s = createRefreshScheduler(() => {}, 1000, { active: true, enabled: true, visible: true })
    s.dispose()
    expect(() => s.update({ active: false })).not.toThrow()
  })

  it("disposed 后 tickNow 返回 resolved promise", async () => {
    const tick = vi.fn(() => Promise.resolve())
    const s = createRefreshScheduler(tick, 1000, { active: false, enabled: true, visible: true })
    // 创建时非运行态，tick 未被调用
    expect(tick).toHaveBeenCalledTimes(0)
    s.dispose()
    const p = s.tickNow()
    expect(p).toBeInstanceOf(Promise)
    await expect(p).resolves.toBeUndefined()
    expect(tick).toHaveBeenCalledTimes(0)
  })
})

describe("liveDataReducer — stale 恢复路径", () => {
  it("失败置 stale 后下一周期成功清 stale（SC-006 自愈）", () => {
    let st = liveDataReducer(initialLiveDataState<number>(), { type: "success", data: 1, at: 100 })
    st = liveDataReducer(st, { type: "start" })
    st = liveDataReducer(st, { type: "error" })
    expect(st.stale).toBe(true)
    expect(st.data).toBe(1) // 保留旧数据
    // 下一周期成功
    st = liveDataReducer(st, { type: "start" })
    st = liveDataReducer(st, { type: "success", data: 2, at: 200 })
    expect(st.stale).toBe(false)
    expect(st.error).toBe(false)
    expect(st.data).toBe(2)
  })
})
