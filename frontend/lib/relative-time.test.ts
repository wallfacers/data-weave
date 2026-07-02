import { describe, it, expect } from "vitest"
import { relativeNextTrigger } from "./relative-time"

const NOW = new Date("2026-07-02T12:00:00Z")
const iso = (base: Date, offsetMs: number) => new Date(base.getTime() + offsetMs).toISOString()
const MIN = 60_000
const HOUR = 3_600_000
const DAY = 86_400_000

describe("relativeNextTrigger", () => {
  it("null/空/无效 → null", () => {
    expect(relativeNextTrigger(null, NOW)).toBeNull()
    expect(relativeNextTrigger("", NOW)).toBeNull()
    expect(relativeNextTrigger("not-a-date", NOW)).toBeNull()
  })

  it("临近（<1min 未来）→ relSoon", () => {
    expect(relativeNextTrigger(iso(NOW, 30_000), NOW)).toEqual({ key: "relSoon" })
  })

  it("未来分钟 → relInMinutes", () => {
    expect(relativeNextTrigger(iso(NOW, 3 * MIN), NOW)).toEqual({ key: "relInMinutes", values: { n: 3 } })
  })

  it("未来小时 → relInHours", () => {
    expect(relativeNextTrigger(iso(NOW, 3 * HOUR), NOW)).toEqual({ key: "relInHours", values: { n: 3 } })
  })

  it("未来天 → relInDays", () => {
    expect(relativeNextTrigger(iso(NOW, 2 * DAY), NOW)).toEqual({ key: "relInDays", values: { n: 2 } })
  })

  it("已过期分钟 → relExpiredMinutes（至少 1）", () => {
    expect(relativeNextTrigger(iso(NOW, -5 * MIN), NOW)).toEqual({ key: "relExpiredMinutes", values: { n: 5 } })
  })

  it("已过期小时 → relExpiredHours", () => {
    expect(relativeNextTrigger(iso(NOW, -2 * HOUR), NOW)).toEqual({ key: "relExpiredHours", values: { n: 2 } })
  })
})
