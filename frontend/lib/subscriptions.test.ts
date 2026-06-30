import { describe, it, expect } from "vitest"
import { findAssetSubscription } from "./subscriptions"
import type { AssetSubscription } from "./catalog-api"

const subs: AssetSubscription[] = [
  { id: 11, targetType: "ASSET", targetId: 5, changeFilter: "schema" },
  { id: 12, targetType: "METRIC", targetId: 5, changeFilter: "freshness" },
  { id: 13, targetType: "ASSET", targetId: 9 },
]

describe("findAssetSubscription（资产订阅判定）", () => {
  it("命中：返回该资产的 ASSET 订阅", () => {
    expect(findAssetSubscription(subs, 5)).toEqual(subs[0])
  })

  it("只认 targetType=ASSET，不误取同 id 的 METRIC 订阅", () => {
    const onlyMetric: AssetSubscription[] = [{ id: 12, targetType: "METRIC", targetId: 5 }]
    expect(findAssetSubscription(onlyMetric, 5)).toBeNull()
  })

  it("未订阅 → null", () => {
    expect(findAssetSubscription(subs, 999)).toBeNull()
  })

  it("空列表 → null", () => {
    expect(findAssetSubscription([], 5)).toBeNull()
  })

  it("命中订阅可取 subId 退订", () => {
    expect(findAssetSubscription(subs, 9)?.id).toBe(13)
  })
})
