import { describe, it, expect } from "vitest"
import { INITIAL_QUERY, toggleFacet, setKeyword, setQualityMin, setPage, buildSearchParams } from "./asset-search-query"

describe("asset-search-query（检索查询构建）", () => {
  it("初值：空查询、page=1", () => {
    expect(INITIAL_QUERY).toEqual({ keyword: "", sensitivity: "", owner: "", tag: "", qualityMin: "", page: 1 })
  })

  it("toggleFacet 点选：设值并复位 page=1", () => {
    const s = toggleFacet({ ...INITIAL_QUERY, page: 3 }, "sensitivity", "PII")
    expect(s.sensitivity).toBe("PII")
    expect(s.page).toBe(1)
  })

  it("toggleFacet 再点同值：取消（清空）", () => {
    const s1 = toggleFacet(INITIAL_QUERY, "owner", "7")
    const s2 = toggleFacet(s1, "owner", "7")
    expect(s2.owner).toBe("")
  })

  it("toggleFacet 点选不同值：替换", () => {
    const s1 = toggleFacet(INITIAL_QUERY, "tag", "a")
    const s2 = toggleFacet(s1, "tag", "b")
    expect(s2.tag).toBe("b")
  })

  it("setKeyword / setPage / setQualityMin", () => {
    expect(setKeyword(INITIAL_QUERY, "orders").keyword).toBe("orders")
    expect(setKeyword({ ...INITIAL_QUERY, page: 4 }, "x").page).toBe(1)
    expect(setPage(INITIAL_QUERY, 2).page).toBe(2)
    expect(setQualityMin(INITIAL_QUERY, "80").qualityMin).toBe("80")
    expect(setQualityMin({ ...INITIAL_QUERY, page: 5 }, "80").page).toBe(1)
  })

  it("buildSearchParams：组装非空项 + qualityMin 转数字", () => {
    const s = { keyword: "o", sensitivity: "PII", owner: "7", tag: "a", qualityMin: "80", page: 2 }
    expect(buildSearchParams(s)).toEqual({ keyword: "o", sensitivity: "PII", owner: "7", tag: "a", qualityMin: 80, page: 2 })
  })

  it("buildSearchParams：空项省略，qualityMin 空/非数字省略，**不含 status**", () => {
    const out = buildSearchParams(INITIAL_QUERY)
    expect(out).toEqual({ page: 1 })
    expect("status" in out).toBe(false)
    expect("qualityMin" in out).toBe(false)
    expect(buildSearchParams({ ...INITIAL_QUERY, qualityMin: "abc" })).toEqual({ page: 1 })
  })
})
