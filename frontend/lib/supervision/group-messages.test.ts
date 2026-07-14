import { describe, it, expect } from "vitest"

import { groupMessages, type RenderRow } from "./group-messages"
import type { IncidentMessage } from "./types"

function msg(
  seq: number,
  kind: IncidentMessage["kind"],
  actor: string | null,
  createdAt: string | null,
): IncidentMessage {
  return {
    id: `m-${seq}`,
    incidentId: "inc",
    seq,
    kind,
    content: `c${seq}`,
    payloadJson: null,
    actor,
    actorName: actor,
    createdAt,
  }
}

function messages(rows: RenderRow[]): { showHeader: boolean; id: string }[] {
  return rows.filter((r) => r.type === "message").map((r) => ({ showHeader: (r as any).showHeader, id: (r as any).msg.id }))
}

describe("groupMessages", () => {
  it("同发言者 5 分钟内连续消息合并（仅组首 showHeader）", () => {
    const rows = groupMessages([
      msg(1, "HUMAN_SAY", "amy", "2026-07-14T10:00:00"),
      msg(2, "HUMAN_SAY", "amy", "2026-07-14T10:02:00"),
      msg(3, "HUMAN_SAY", "amy", "2026-07-14T10:04:00"),
    ])
    expect(messages(rows).map((m) => m.showHeader)).toEqual([true, false, false])
  })

  it("超过 5 分钟间隔重新起组", () => {
    const rows = groupMessages([
      msg(1, "HUMAN_SAY", "amy", "2026-07-14T10:00:00"),
      msg(2, "HUMAN_SAY", "amy", "2026-07-14T10:06:00"), // 6min gap
    ])
    expect(messages(rows).map((m) => m.showHeader)).toEqual([true, true])
  })

  it("正好 5 分钟仍合并（边界含）", () => {
    const rows = groupMessages([
      msg(1, "HUMAN_SAY", "amy", "2026-07-14T10:00:00"),
      msg(2, "HUMAN_SAY", "amy", "2026-07-14T10:05:00"), // exactly 5min
    ])
    expect(messages(rows).map((m) => m.showHeader)).toEqual([true, false])
  })

  it("不同发言者交替各自起组", () => {
    const rows = groupMessages([
      msg(1, "HUMAN_SAY", "amy", "2026-07-14T10:00:00"),
      msg(2, "AGENT_SAY", "ops-agent", "2026-07-14T10:00:30"),
      msg(3, "HUMAN_SAY", "amy", "2026-07-14T10:01:00"),
    ])
    expect(messages(rows).map((m) => m.showHeader)).toEqual([true, true, true])
  })

  it("不同人类发言者不合并", () => {
    const rows = groupMessages([
      msg(1, "HUMAN_SAY", "amy", "2026-07-14T10:00:00"),
      msg(2, "HUMAN_SAY", "bob", "2026-07-14T10:01:00"),
    ])
    expect(messages(rows).map((m) => m.showHeader)).toEqual([true, true])
  })

  it("跨自然日插入 DateSeparator 且首条 showHeader", () => {
    const rows = groupMessages([
      msg(1, "HUMAN_SAY", "amy", "2026-07-14T23:59:00"),
      msg(2, "HUMAN_SAY", "amy", "2026-07-15T00:01:00"),
    ])
    const dateRows = rows.filter((r) => r.type === "date")
    expect(dateRows).toHaveLength(2) // 每个自然日首条各一个分隔（首条也算 day 变化）
    expect(messages(rows).map((m) => m.showHeader)).toEqual([true, true])
  })
})
