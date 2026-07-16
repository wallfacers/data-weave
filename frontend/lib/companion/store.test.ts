import { describe, it, expect, beforeEach } from "vitest"
import { useCompanionStore } from "./store"
import type { ReportView, MessageView } from "./types"

function makeReport(overrides: Partial<ReportView> = {}): ReportView {
  return {
    id: "r1",
    domain: "TASK_FAILURE",
    severity: "DANGER",
    title: "Test Report",
    summary: "Summary",
    aggregateCount: 1,
    status: "UNREAD",
    createdAt: "2026-07-15T12:00:00Z",
    ...overrides,
  }
}

function makeMessage(overrides: Partial<MessageView> = {}): MessageView {
  return {
    id: "m1",
    role: "AGENT",
    actorName: "Vega",
    content: "Hello",
    createdAt: "2026-07-15T12:00:00Z",
    ...overrides,
  }
}

describe("useCompanionStore", () => {
  beforeEach(() => {
    useCompanionStore.setState({
      state: "idle",
      briefing: { todayRuns: 0, openAnomalies: 0, nextPatrolAt: null },
      reports: [],
      messages: [],
      anchorReportId: null,
      connection: "disconnected",
    })
  })

  it("初始状态为 idle", () => {
    expect(useCompanionStore.getState().state).toBe("idle")
  })

  it("setCompanionState 切换管家形态", () => {
    useCompanionStore.getState().setCompanionState("alert")
    expect(useCompanionStore.getState().state).toBe("alert")
  })

  it("setBriefing 更新概况", () => {
    useCompanionStore.getState().setBriefing({
      todayRuns: 5,
      openAnomalies: 2,
      nextPatrolAt: "14:30",
    })
    expect(useCompanionStore.getState().briefing).toEqual({
      todayRuns: 5,
      openAnomalies: 2,
      nextPatrolAt: "14:30",
    })
  })

  it("addReport 添加新汇报（置顶）", () => {
    const r1 = makeReport({ id: "r1" })
    const r2 = makeReport({ id: "r2" })
    useCompanionStore.getState().addReport(r1)
    useCompanionStore.getState().addReport(r2)
    const reports = useCompanionStore.getState().reports
    expect(reports).toHaveLength(2)
    expect(reports[0].id).toBe("r2") // newest first
  })

  it("addReport 重复 id 覆盖而非新增", () => {
    const r1 = makeReport({ id: "r1", title: "First" })
    useCompanionStore.getState().addReport(r1)
    const r1Updated = makeReport({ id: "r1", title: "Updated" })
    useCompanionStore.getState().addReport(r1Updated)
    expect(useCompanionStore.getState().reports).toHaveLength(1)
    expect(useCompanionStore.getState().reports[0].title).toBe("Updated")
  })

  it("removeReport 按 id 移除", () => {
    useCompanionStore.getState().addReport(makeReport({ id: "r1" }))
    useCompanionStore.getState().addReport(makeReport({ id: "r2" }))
    useCompanionStore.getState().removeReport("r1")
    expect(useCompanionStore.getState().reports).toHaveLength(1)
    expect(useCompanionStore.getState().reports[0].id).toBe("r2")
  })

  it("addMessage 追加消息", () => {
    useCompanionStore.getState().addMessage(makeMessage({ id: "m1" }))
    useCompanionStore.getState().addMessage(makeMessage({ id: "m2" }))
    expect(useCompanionStore.getState().messages).toHaveLength(2)
  })

  it("appendDelta 追加流式 chunk 到已有消息", () => {
    useCompanionStore.getState().addMessage(makeMessage({ id: "m1", content: "Hello" }))
    useCompanionStore.getState().appendDelta("m1", " World")
    const msgs = useCompanionStore.getState().messages
    expect(msgs[0].content).toBe("Hello World")
  })

  it("appendDelta 对不存在的 messageId 创建占位消息（delta 早于 message 事件）", () => {
    useCompanionStore.getState().appendDelta("early", "chunk")
    const msgs = useCompanionStore.getState().messages
    expect(msgs).toHaveLength(1)
    expect(msgs[0].id).toBe("early")
    expect(msgs[0].content).toBe("chunk")
    expect(msgs[0].role).toBe("AGENT")
  })

  it("setConnection 更新连接状态", () => {
    useCompanionStore.getState().setConnection("live")
    expect(useCompanionStore.getState().connection).toBe("live")
  })

  it("五形态全量遍历无误", () => {
    const states = ["idle", "patrol", "alert", "think", "speak"] as const
    for (const s of states) {
      useCompanionStore.getState().setCompanionState(s)
      expect(useCompanionStore.getState().state).toBe(s)
    }
  })

  it("endMessage 设置 interrupted 标记", () => {
    useCompanionStore.getState().addMessage(makeMessage({ id: "m1", content: "Hello" }))
    useCompanionStore.getState().endMessage("m1", true)
    expect(useCompanionStore.getState().messages[0].content).toContain("⌟")
  })

  it("endMessage 不设置 interrupted 标记", () => {
    useCompanionStore.getState().addMessage(makeMessage({ id: "m2", content: "World" }))
    useCompanionStore.getState().endMessage("m2", false)
    expect(useCompanionStore.getState().messages[0].content).toBe("World")
  })

  it("appendDelta 早于 message 时创建占位消息", () => {
    useCompanionStore.getState().appendDelta("early", "chunk")
    const msgs = useCompanionStore.getState().messages
    expect(msgs).toHaveLength(1)
    expect(msgs[0].id).toBe("early")
    expect(msgs[0].content).toBe("chunk")
  })

  /* ── 073 US1/US3 Foundational：历史合并去重 + 幂等 + 锚定 ── */

  it("setMessages 合并历史并按 id 去重（实时+历史不重复）", () => {
    // 已有一条实时消息
    useCompanionStore.getState().addMessage(makeMessage({ id: "m1", content: "live" }))
    // 历史加载返回 m1（重复）+ m2（新）
    useCompanionStore.getState().setMessages([
      makeMessage({ id: "m1", content: "live" }),
      makeMessage({ id: "m2", content: "history" }),
    ])
    const msgs = useCompanionStore.getState().messages
    expect(msgs).toHaveLength(2)
    expect(msgs.map((m) => m.id).sort()).toEqual(["m1", "m2"])
  })

  it("setMessages 不用历史短快照覆盖在途流的较长 content", () => {
    // 在途流已累积较长内容
    useCompanionStore.getState().addMessage(makeMessage({ id: "m1", content: "streamed long content" }))
    // 历史返回同 id 的较短（旧）快照
    useCompanionStore.getState().setMessages([makeMessage({ id: "m1", content: "short" })])
    expect(useCompanionStore.getState().messages[0].content).toBe("streamed long content")
  })

  it("addMessage 幂等：重复 id 覆盖而非新增", () => {
    useCompanionStore.getState().addMessage(makeMessage({ id: "m1", content: "v1" }))
    useCompanionStore.getState().addMessage(makeMessage({ id: "m1", content: "v2 longer" }))
    const msgs = useCompanionStore.getState().messages
    expect(msgs).toHaveLength(1)
    expect(msgs[0].content).toBe("v2 longer")
  })

  it("setAnchor 设/清锚定问题", () => {
    useCompanionStore.getState().setAnchor("r1")
    expect(useCompanionStore.getState().anchorReportId).toBe("r1")
    useCompanionStore.getState().setAnchor(null)
    expect(useCompanionStore.getState().anchorReportId).toBeNull()
  })

  it("removeReport 命中当前锚定 → 回落全局（anchorReportId=null）", () => {
    useCompanionStore.getState().addReport(makeReport({ id: "r1" }))
    useCompanionStore.getState().setAnchor("r1")
    useCompanionStore.getState().removeReport("r1")
    expect(useCompanionStore.getState().anchorReportId).toBeNull()
  })

  it("removeReport 未命中锚定 → 锚定保持", () => {
    useCompanionStore.getState().addReport(makeReport({ id: "r1" }))
    useCompanionStore.getState().addReport(makeReport({ id: "r2" }))
    useCompanionStore.getState().setAnchor("r1")
    useCompanionStore.getState().removeReport("r2")
    expect(useCompanionStore.getState().anchorReportId).toBe("r1")
  })
})
