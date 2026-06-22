import { describe, expect, it } from "vitest"

import { deriveRunDotState, parseEndState, runDotColor, type RunDotState } from "./run-dot-state"

describe("parseEndState", () => {
  it("解析标准 end 负载", () => {
    expect(parseEndState('{"state":"SUCCESS"}')).toBe("SUCCESS")
    expect(parseEndState('{"state":"FAILED"}')).toBe("FAILED")
    expect(parseEndState('{"state":"STOPPED"}')).toBe("STOPPED")
  })

  it("空负载返回 null 且不抛（4.3）", () => {
    expect(parseEndState(null)).toBeNull()
    expect(parseEndState("")).toBeNull()
    expect(parseEndState(undefined)).toBeNull()
  })

  it("非 JSON / 旧负载返回 null 且不抛、不误判终态（4.3）", () => {
    expect(parseEndState("not-json")).toBeNull()
    expect(parseEndState('{"foo":"bar"}')).toBeNull() // 无 state 字段
    expect(parseEndState('{"state":123}')).toBeNull() // state 非 string
  })
})

describe("deriveRunDotState", () => {
  it("运行中：connected 且无终态 outcome", () => {
    expect(deriveRunDotState(null, false, true)).toBe("running")
  })

  it("终态 outcome 覆盖连接态且不回退（4.2）", () => {
    // 即使 connected=true，终态 outcome 一锤定音，不再回退运行中
    expect(deriveRunDotState("SUCCESS", true, true)).toBe("success")
    expect(deriveRunDotState("FAILED", true, true)).toBe("failed")
    expect(deriveRunDotState("STOPPED", true, true)).toBe("stopped")
  })

  it("已 end 但无 outcome（兼容旧/空负载）→ 中性灰，不臆测成败", () => {
    expect(deriveRunDotState(null, true, false)).toBe("stopped")
    expect(deriveRunDotState(null, true, true)).toBe("stopped") // 即使仍 connected，已 end 优先中性
  })

  it("未知 outcome 值落到 ended 分支中性灰", () => {
    expect(deriveRunDotState("WAITING", true, false)).toBe("stopped")
  })

  it("连接中：无 outcome、未 end、未 connected", () => {
    expect(deriveRunDotState(null, false, false)).toBe("connecting")
  })
})

describe("runDotColor 状态→颜色映射（4.1）", () => {
  it("三种终态颜色", () => {
    expect(runDotColor[deriveRunDotState("SUCCESS", true, false)]).toBe("bg-success")
    expect(runDotColor[deriveRunDotState("FAILED", true, false)]).toBe("bg-destructive")
    expect(runDotColor[deriveRunDotState("STOPPED", true, false)]).toBe("bg-muted-foreground")
  })

  it("运行中绿色脉冲、连接中琥珀脉冲", () => {
    expect(runDotColor[deriveRunDotState(null, false, true)]).toBe("bg-success animate-pulse")
    expect(runDotColor[deriveRunDotState(null, false, false)]).toBe("bg-warning animate-pulse")
  })

  it("全部状态非空且使用语义 token（无硬编码 emerald/amber）", () => {
    const all: RunDotState[] = ["running", "success", "failed", "stopped", "connecting"]
    for (const s of all) {
      expect(runDotColor[s]).toBeTruthy()
      expect(runDotColor[s]).not.toMatch(/emerald|amber/)
    }
  })
})
