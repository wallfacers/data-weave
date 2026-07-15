import { describe, expect, it } from "vitest"

import { initialState, reduce, selectFeed, selectLive, selectMessages } from "./store"
import type { Incident, IncidentMessage } from "./types"

function incident(id: string, state: Incident["state"], openedAt = "2026-07-14T00:00:00"): Incident {
  return {
    id,
    tenantId: 1,
    projectId: 1,
    taskDefId: 1,
    taskDefName: "t-" + id,
    firstInstanceId: "i1",
    latestInstanceId: "i1",
    instanceCount: 1,
    triggerSource: "CRON",
    classification: null,
    confidence: null,
    state,
    openKey: state === "RESOLVED" ? null : 1,
    autoActionCount: 0,
    summary: null,
    suggestion: null,
    closeKind: null,
    openedAt,
    closedAt: null,
    version: 0,
    createdAt: openedAt,
    updatedAt: openedAt,
  }
}

function message(incidentId: string, seq: number, kind: IncidentMessage["kind"], content: string, payloadJson: string | null = null): IncidentMessage {
  return { id: `${incidentId}-${seq}`, incidentId, seq, kind, content, payloadJson, actor: "ops-agent", actorName: null, createdAt: "2026-07-14T00:00:00" }
}

describe("supervision store reducer", () => {
  it("snapshot 建表 + 实时数字", () => {
    const s = reduce(initialState(), {
      type: "snapshot",
      incidents: [incident("a", "ACTING")],
      stats: { active: 1, agentWorking: 1, awaitingApproval: 0, needsHuman: 0, resolvedToday: 0 },
    })
    expect(Object.keys(s.incidents)).toHaveLength(1)
    expect(s.stats?.agentWorking).toBe(1)
  })

  it("incident 事件 upsert 覆盖同 id", () => {
    let s = reduce(initialState(), { type: "incident", incident: incident("a", "ACTING") })
    s = reduce(s, { type: "incident", incident: incident("a", "NEEDS_HUMAN") })
    expect(s.incidents["a"].state).toBe("NEEDS_HUMAN")
    expect(Object.keys(s.incidents)).toHaveLength(1)
  })

  it("selectFeed 置顶待处理，其余按 openedAt 倒序", () => {
    let s = initialState()
    s = reduce(s, { type: "incident", incident: incident("a", "ACTING", "2026-07-14T01:00:00") })
    s = reduce(s, { type: "incident", incident: incident("b", "NEEDS_HUMAN", "2026-07-14T02:00:00") })
    s = reduce(s, { type: "incident", incident: incident("c", "ACTING", "2026-07-14T03:00:00") })
    const { pending, rest } = selectFeed(s)
    expect(pending.map((i) => i.id)).toEqual(["b"])
    expect(rest.map((i) => i.id)).toEqual(["c", "a"]) // 03:00 先于 01:00
  })

  it("selectFeed 按状态过滤", () => {
    let s = initialState()
    s = reduce(s, { type: "incident", incident: incident("a", "ACTING") })
    s = reduce(s, { type: "incident", incident: incident("b", "NEEDS_HUMAN") })
    const { pending, rest } = selectFeed(s, { state: "NEEDS_HUMAN" })
    expect(pending.map((i) => i.id)).toEqual(["b"])
    expect(rest).toHaveLength(0)
  })

  it("message 去重追加（by seq），断线补齐不叠加", () => {
    let s = initialState()
    s = reduce(s, { type: "message", incidentId: "a", message: message("a", 1, "AGENT_STEP", "诊断") })
    s = reduce(s, { type: "message", incidentId: "a", message: message("a", 2, "ACTION", "重跑") })
    s = reduce(s, { type: "message", incidentId: "a", message: message("a", 1, "AGENT_STEP", "诊断") }) // 重复 seq
    expect(selectMessages(s, "a")).toHaveLength(2)
  })

  it("delta 同 streamId 拼接；对应 message（payload.streamId 匹配）收尾清空缓冲", () => {
    let s = initialState()
    s = reduce(s, { type: "delta", incidentId: "a", streamId: "sx", text: "你好" })
    s = reduce(s, { type: "delta", incidentId: "a", streamId: "sx", text: "，世界" })
    expect(selectLive(s, "a").delta?.text).toBe("你好，世界")
    // 落库 AGENT_SAY 带 streamId=sx → 打字流缓冲被清空
    s = reduce(s, {
      type: "message",
      incidentId: "a",
      message: message("a", 3, "AGENT_SAY", "你好，世界", JSON.stringify({ streamId: "sx" })),
    })
    expect(selectLive(s, "a").delta).toBeNull()
  })

  it("thinking START/STOP 切换", () => {
    let s = initialState()
    s = reduce(s, { type: "thinking", incidentId: "a", phase: "START", label: "分析中" })
    expect(selectLive(s, "a").thinking).toEqual({ active: true, label: "分析中" })
    s = reduce(s, { type: "thinking", incidentId: "a", phase: "STOP", label: null })
    expect(selectLive(s, "a").thinking.active).toBe(false)
  })

  it("chip 按 chipId upsert（RUNNING→DONE 原地替换）", () => {
    let s = initialState()
    s = reduce(s, { type: "chip", incidentId: "a", chipId: "code", label: "分析代码", status: "RUNNING" })
    s = reduce(s, { type: "chip", incidentId: "a", chipId: "code", label: "分析代码", status: "DONE" })
    const chips = selectLive(s, "a").chips
    expect(chips).toHaveLength(1)
    expect(chips[0].status).toBe("DONE")
  })

  it("briefing 事件更新综述与实时数字", () => {
    const s = reduce(initialState(), {
      type: "briefing",
      summaryLine: "3 起活跃",
      stats: { active: 3, agentWorking: 1, awaitingApproval: 1, needsHuman: 1, resolvedToday: 0 },
      generatedAt: "2026-07-14T04:00:00",
    })
    expect(s.briefing?.summaryLine).toBe("3 起活跃")
    expect(s.stats?.active).toBe(3)
  })
})

describe("US2 连接三态（connectionPhase）", () => {
  it("初始为 connecting（首帧未达，不冒充真空态）", () => {
    const s = initialState()
    expect(s.connectionPhase).toBe("connecting")
    expect(s.connected).toBe(false)
  })

  it("首个 snapshot 到达 → live（此后空 feed 才是真无事故）", () => {
    const s = reduce(initialState(), { type: "snapshot", incidents: [], stats: null })
    expect(s.connectionPhase).toBe("live")
    expect(s.connected).toBe(true)
  })

  it("phase=degraded 断线不清空已加载消息", () => {
    let s = initialState()
    s = reduce(s, { type: "message", incidentId: "a", message: message("a", 1, "AGENT_SAY", "已诊断") })
    s = reduce(s, { type: "phase", value: "degraded" })
    expect(s.connectionPhase).toBe("degraded")
    expect(s.connected).toBe(false)
    expect(selectMessages(s, "a")).toHaveLength(1) // 消息保留
  })

  it("degraded 后重新 snapshot → 回 live", () => {
    let s = reduce(initialState(), { type: "phase", value: "degraded" })
    s = reduce(s, { type: "snapshot", incidents: [incident("a", "ACTING")], stats: null })
    expect(s.connectionPhase).toBe("live")
    expect(s.connected).toBe(true)
  })

  it("兼容旧 connected 派发映射到相位", () => {
    expect(reduce(initialState(), { type: "connected", value: true }).connectionPhase).toBe("live")
    expect(reduce(initialState(), { type: "connected", value: false }).connectionPhase).toBe("degraded")
  })
})
