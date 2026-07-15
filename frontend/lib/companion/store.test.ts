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
      connection: "disconnected",
    })
  })

  it("初始状态为 idle", () => {
    expect(useCompanionStore.getState().state).toBe("idle")
  })

  it("setState 切换管家形态", () => {
    useCompanionStore.getState().setState("alert")
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

  it("appendDelta 对不存在的 messageId 无操作", () => {
    useCompanionStore.getState().appendDelta("nonexistent", "chunk")
    expect(useCompanionStore.getState().messages).toHaveLength(0)
  })

  it("setConnection 更新连接状态", () => {
    useCompanionStore.getState().setConnection("live")
    expect(useCompanionStore.getState().connection).toBe("live")
  })

  it("五形态全量遍历无误", () => {
    const states = ["idle", "patrol", "alert", "think", "speak"] as const
    for (const s of states) {
      useCompanionStore.getState().setState(s)
      expect(useCompanionStore.getState().state).toBe(s)
    }
  })
})
