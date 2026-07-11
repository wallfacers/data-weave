import { describe, it, expect } from "vitest"
import { humanizeDuration, STATE_BADGE } from "./streaming-tasks-panel"

/**
 * 062 US1 实时任务面板纯逻辑单测：已运行时长可读化 + 状态 badge 映射。
 * （DataTable server-fetch 面板的渲染由 typecheck + 浏览器验证覆盖；此处聚焦有分支的纯逻辑。）
 */
describe("humanizeDuration", () => {
  it.each([
    [null, "—"],
    [undefined, "—"],
    [-5, "—"],
    [0, "0s"],
    [45, "45s"],
    [90, "1m 30s"],
    [3661, "1h 1m"],
    [90000, "1d 1h"],
  ])("%s → %s", (sec, expected) => {
    expect(humanizeDuration(sec as number)).toBe(expected)
  })
})

describe("STATE_BADGE", () => {
  it("SUSPENDED 用 warning 醒目（一等化）", () => {
    expect(STATE_BADGE.SUSPENDED).toBe("warning")
  })
  it("RUNNING 用 success", () => {
    expect(STATE_BADGE.RUNNING).toBe("success")
  })
  it("FAILED 用 destructive", () => {
    expect(STATE_BADGE.FAILED).toBe("destructive")
  })
})
