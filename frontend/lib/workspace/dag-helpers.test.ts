import { describe, it, expect } from "vitest"
import { wouldCreateCycle } from "./dag-helpers"

describe("wouldCreateCycle", () => {
  const e = (s: string, t: string) => ({ source: s, target: t })

  it("空图任意连边不成环", () => {
    expect(wouldCreateCycle([], "a", "b")).toBe(false)
  })

  it("自指视为成环", () => {
    expect(wouldCreateCycle([], "a", "a")).toBe(true)
  })

  it("反向路径已存在则成环（b→a 在，连 a→b）", () => {
    expect(wouldCreateCycle([e("b", "a")], "a", "b")).toBe(true)
  })

  it("顺向无环路径不成环（a→b→c，连 a→c）", () => {
    expect(wouldCreateCycle([e("a", "b"), e("b", "c")], "a", "c")).toBe(false)
  })

  it("多跳成环（a→b→c，连 c→a）", () => {
    expect(wouldCreateCycle([e("a", "b"), e("b", "c")], "c", "a")).toBe(true)
  })

  it("不相通的分支不误判成环", () => {
    // a→b 与 c→d 独立，连 a→c 不成环
    expect(wouldCreateCycle([e("a", "b"), e("c", "d")], "a", "c")).toBe(false)
  })
})
