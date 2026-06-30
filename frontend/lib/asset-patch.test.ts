import { describe, it, expect } from "vitest"
import { diffPatch } from "./asset-patch"

const FIELDS = ["name", "description", "ownerId", "sensitivity", "tags", "lineageTableRef"] as const

type Form = {
  name: string
  description: string
  ownerId: number | null
  sensitivity: string
  tags: string[]
  lineageTableRef: string
}

const base: Form = {
  name: "orders",
  description: "daily orders",
  ownerId: 1,
  sensitivity: "INTERNAL",
  tags: ["a", "b"],
  lineageTableRef: "db.orders",
}

describe("diffPatch（部分更新语义）", () => {
  it("无改动 → 空 patch", () => {
    expect(diffPatch(base, { ...base }, [...FIELDS])).toEqual({})
  })

  it("仅含改动键", () => {
    const next = { ...base, description: "changed", ownerId: 2 }
    expect(diffPatch(base, next, [...FIELDS])).toEqual({ description: "changed", ownerId: 2 })
  })

  it("数组按元素比较：相同顺序不算改动", () => {
    const next = { ...base, tags: ["a", "b"] }
    expect(diffPatch(base, next, [...FIELDS])).toEqual({})
  })

  it("数组元素变化算改动", () => {
    const next = { ...base, tags: ["a", "c"] }
    expect(diffPatch(base, next, [...FIELDS])).toEqual({ tags: ["a", "c"] })
  })

  it("显式清空（→ 空串/null）算改动", () => {
    const next = { ...base, lineageTableRef: "", ownerId: null }
    expect(diffPatch(base, next, [...FIELDS])).toEqual({ lineageTableRef: "", ownerId: null })
  })

  it("白名单外字段不进 patch", () => {
    const next = { ...base, name: "x", extra: "y" } as unknown as Form
    expect(diffPatch(base, next, ["description"])).toEqual({})
  })
})
