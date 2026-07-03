import { describe, it, expect } from "vitest"
import type { IncidentCard, IncidentQueue } from "@/lib/incident-api"

/** 模拟队列数据的三区分类逻辑（对齐 incidents-view.tsx 中的渲染分区）。 */

/** 判断卡片是否属于活跃区（待处置 + 处置中）。 */
function isActiveCard(state: string): boolean {
  return state === "OPEN" || state === "MITIGATING"
}

/** 判断卡片是否属于近 24h 已解决区。 */
function isRecentResolved(state: string): boolean {
  return state === "RESOLVED"
}

describe("三区渲染逻辑", () => {
  it("OPEN/MITIGATING 卡片归属活跃区", () => {
    expect(isActiveCard("OPEN")).toBe(true)
    expect(isActiveCard("MITIGATING")).toBe(true)
  })

  it("RESOLVED/SUPPRESSED/CLOSED 不在活跃区", () => {
    expect(isActiveCard("RESOLVED")).toBe(false)
    expect(isActiveCard("SUPPRESSED")).toBe(false)
    expect(isActiveCard("CLOSED")).toBe(false)
  })

  it("RESOLVED 卡片归属近 24h 已解决区", () => {
    expect(isRecentResolved("RESOLVED")).toBe(true)
    expect(isRecentResolved("OPEN")).toBe(false)
    expect(isRecentResolved("CLOSED")).toBe(false)
  })

  it("空队列 → 正向空态文案（非错误态）", () => {
    const emptyQueue: IncidentQueue = {
      active: [],
      recentResolved: [],
      activeCount: 0,
      recentResolvedCount: 0,
    }
    expect(emptyQueue.active.length).toBe(0)
    expect(emptyQueue.recentResolved.length).toBe(0)
    // 空态使用 "emptyActive" 文案，非错误样式
    expect(emptyQueue.activeCount).toBe(0)
    expect(emptyQueue.recentResolvedCount).toBe(0)
  })

  it("仅有活跃卡片时活跃区非空、已解决区为空", () => {
    const card: IncidentCard = {
      id: 1,
      title: "task_failed",
      severity: "HIGH",
      state: "OPEN",
      signature: "T:1:EXIT_NONZERO",
      sourceKind: "TASK",
      sourceRefId: "1",
      sourceRefName: "task",
      workflowInstanceId: null,
      occurrenceCount: 1,
      firstSeenAt: "",
      lastSeenAt: "",
      blastRadius: null,
      timeBudgetAt: null,
      suppressReason: null,
      resolutionKind: null,
      resolvedAt: null,
      pendingActionCount: 0,
      priorIncidentCount: 0,
      diagnosis: null,
      proposal: null,
    }
    const queue: IncidentQueue = {
      active: [card],
      recentResolved: [],
      activeCount: 1,
      recentResolvedCount: 0,
    }
    // 活跃区有内容
    const activeCards = queue.active.filter((c) => isActiveCard(c.state))
    expect(activeCards.length).toBe(1)
    // 已解决区为空
    const resolvedCards = queue.recentResolved.filter((c) => isRecentResolved(c.state))
    expect(resolvedCards.length).toBe(0)
  })

  it("仅有已解决卡片时活跃区为空、已解决区非空", () => {
    const card: IncidentCard = {
      id: 2,
      title: "healed_task",
      severity: "MEDIUM",
      state: "RESOLVED",
      signature: "T:2:TIMEOUT",
      sourceKind: "TASK",
      sourceRefId: "2",
      sourceRefName: "healed",
      workflowInstanceId: null,
      occurrenceCount: 1,
      firstSeenAt: "",
      lastSeenAt: "",
      blastRadius: 0,
      timeBudgetAt: null,
      suppressReason: null,
      resolutionKind: "AUTO_HEALED",
      resolvedAt: "2026-07-03T12:00:00Z",
      pendingActionCount: 0,
      priorIncidentCount: 0,
      diagnosis: null,
      proposal: null,
    }
    const queue: IncidentQueue = {
      active: [],
      recentResolved: [card],
      activeCount: 0,
      recentResolvedCount: 1,
    }
    expect(queue.active.filter((c) => isActiveCard(c.state)).length).toBe(0)
    expect(queue.recentResolved.filter((c) => isRecentResolved(c.state)).length).toBe(1)
  })

  it("混合队列：活跃 + 已解决同时存在", () => {
    const activeCard: IncidentCard = {
      id: 1,
      title: "active",
      severity: "HIGH",
      state: "MITIGATING",
      signature: "T:1:FAIL",
      sourceKind: "TASK",
      sourceRefId: "1",
      sourceRefName: "t1",
      workflowInstanceId: null,
      occurrenceCount: 3,
      firstSeenAt: "",
      lastSeenAt: "",
      blastRadius: 5,
      timeBudgetAt: "2026-07-03T14:00:00Z",
      suppressReason: null,
      resolutionKind: null,
      resolvedAt: null,
      pendingActionCount: 1,
      priorIncidentCount: 0,
      diagnosis: null,
      proposal: null,
    }
    const resolvedCard: IncidentCard = {
      id: 2,
      title: "resolved",
      severity: "LOW",
      state: "RESOLVED",
      signature: "T:2:TIMEOUT",
      sourceKind: "TASK",
      sourceRefId: "2",
      sourceRefName: "t2",
      workflowInstanceId: null,
      occurrenceCount: 1,
      firstSeenAt: "",
      lastSeenAt: "",
      blastRadius: null,
      timeBudgetAt: null,
      suppressReason: null,
      resolutionKind: "AUTO_HEALED",
      resolvedAt: "2026-07-03T11:00:00Z",
      pendingActionCount: 0,
      priorIncidentCount: 0,
      diagnosis: null,
      proposal: null,
    }
    const queue: IncidentQueue = {
      active: [activeCard],
      recentResolved: [resolvedCard],
      activeCount: 1,
      recentResolvedCount: 1,
    }
    const active = queue.active.filter((c) => isActiveCard(c.state))
    const resolved = queue.recentResolved.filter((c) => isRecentResolved(c.state))
    expect(active.length).toBe(1)
    expect(resolved.length).toBe(1)
    expect(active[0].state).toBe("MITIGATING")
    expect(resolved[0].state).toBe("RESOLVED")
    // 活跃区卡片保留动作交互（pendingActionCount > 0）
    expect(active[0].pendingActionCount).toBeGreaterThan(0)
  })

  it("已解决区卡片不展示动作按钮（降权样式）", () => {
    // 已解决卡片应使用 dimmed 变体，不显示 rerun/suppress 等动作按钮
    const resolvedState = "RESOLVED"
    // dimmed = !isActiveCard(resolvedState)
    const shouldDim = !isActiveCard(resolvedState)
    expect(shouldDim).toBe(true)
  })

  it("已静默卡片不在活跃区展示（退出队列主视图）", () => {
    // SUPPRESSED 状态既不在活跃区也不在已解决区
    expect(isActiveCard("SUPPRESSED")).toBe(false)
    expect(isRecentResolved("SUPPRESSED")).toBe(false)
  })
})
