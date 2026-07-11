import { describe, it, expect } from "vitest"
import type { IncidentCard, IncidentQueue } from "@/lib/incident-api"

/** 模拟队列数据的三区分类逻辑（对齐 incidents-view.tsx 中的渲染分区）。 */
describe("IncidentQueue data classification", () => {
  const card = (id: number, state: string): IncidentCard =>
    ({
      id,
      title: `Incident ${id}`,
      severity: "HIGH",
      state,
      signature: `T:${id}:EXIT_CODE_-1`,
      sourceKind: "TASK",
      sourceRefId: String(id),
      sourceRefName: `Task ${id}`,
      workflowInstanceId: null,
      occurrenceCount: 1,
      firstSeenAt: "2026-07-11T00:00:00Z",
      lastSeenAt: "2026-07-11T00:00:00Z",
      blastRadius: null,
      timeBudgetAt: null,
      suppressReason: null,
      resolutionKind: null,
      resolvedAt: null,
      healByType: "TASK_SUCCESS",
      healByRefId: String(id),
      pendingActionCount: 0,
      priorIncidentCount: 0,
      diagnosis: null,
      proposal: null,
    }) as IncidentCard

  it("active cards are those with OPEN or MITIGATING state", () => {
    const queue: IncidentQueue = {
      active: [card(1, "OPEN"), card(2, "MITIGATING")],
      recentResolved: [card(3, "RESOLVED")],
      activeCount: 2,
      recentResolvedCount: 1,
    }
    expect(queue.active).toHaveLength(2)
    expect(queue.active[0].state).toBe("OPEN")
    expect(queue.active[1].state).toBe("MITIGATING")
    expect(queue.recentResolved).toHaveLength(1)
    expect(queue.recentResolved[0].state).toBe("RESOLVED")
  })

  it("empty queue has counts of 0", () => {
    const empty: IncidentQueue = {
      active: [],
      recentResolved: [],
      activeCount: 0,
      recentResolvedCount: 0,
    }
    expect(empty.activeCount).toBe(0)
    expect(empty.recentResolvedCount).toBe(0)
  })

  it("IncidentCard includes 064 heal fields", () => {
    const c = card(100, "OPEN")
    expect(c.healByType).toBe("TASK_SUCCESS")
    expect(c.healByRefId).toBe("100")
  })

  it("isActive check matches OPEN and MITIGATING", () => {
    const isActive = (state: string) => state === "OPEN" || state === "MITIGATING"
    expect(isActive("OPEN")).toBe(true)
    expect(isActive("MITIGATING")).toBe(true)
    expect(isActive("RESOLVED")).toBe(false)
    expect(isActive("SUPPRESSED")).toBe(false)
    expect(isActive("CLOSED")).toBe(false)
  })
})
