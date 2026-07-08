import { describe, expect, it, beforeEach } from "vitest"
import {
  getRecentAssets,
  recordRecentAsset,
  clearRecentAssets,
  MAX_RECENT,
} from "@/lib/workspace/lineage-recent"

describe("lineage-recent（会话最近资产）", () => {
  beforeEach(() => clearRecentAssets())

  it("空态返回 []", () => {
    expect(getRecentAssets()).toEqual([])
  })

  it("记一次锚定后可读回，最近优先", () => {
    recordRecentAsset({ id: "t1", name: "user", type: "TABLE" })
    recordRecentAsset({ id: "t2", name: "order", type: "TABLE" })
    expect(getRecentAssets().map((a) => a.id)).toEqual(["t2", "t1"])
  })

  it("按 id 去重且置顶（重复锚定不产生两条）", () => {
    recordRecentAsset({ id: "t1", name: "user", type: "TABLE" })
    recordRecentAsset({ id: "t2", name: "order", type: "TABLE" })
    recordRecentAsset({ id: "t1", name: "user", type: "TABLE" })
    const ids = getRecentAssets().map((a) => a.id)
    expect(ids).toEqual(["t1", "t2"])
    expect(ids.filter((x) => x === "t1")).toHaveLength(1)
  })

  it("截断至上限 MAX_RECENT", () => {
    for (let i = 0; i < MAX_RECENT + 5; i++) {
      recordRecentAsset({ id: `t${i}`, name: `n${i}`, type: "TABLE" })
    }
    expect(getRecentAssets()).toHaveLength(MAX_RECENT)
    // 最新的在最前
    expect(getRecentAssets()[0].id).toBe(`t${MAX_RECENT + 4}`)
  })

  it("空 id 忽略、保留 datasourceName", () => {
    recordRecentAsset({ id: "", name: "x", type: "TABLE" })
    expect(getRecentAssets()).toEqual([])
    recordRecentAsset({ id: "t1", name: "user", type: "TABLE", datasourceName: "mysql-prod" })
    expect(getRecentAssets()[0].datasourceName).toBe("mysql-prod")
  })
})
