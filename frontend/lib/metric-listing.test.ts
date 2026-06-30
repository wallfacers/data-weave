import { describe, it, expect } from "vitest"
import { buildListingPayload, type MetricCard, type ListingForm } from "./metric-listing"

const card: MetricCard = {
  id: 7,
  code: "gmv_daily",
  name: "日 GMV",
  unit: "CNY",
  versionNo: 3,
  status: "ACTIVE",
  value: 123,
}

describe("buildListingPayload（上架载荷构建）", () => {
  it("从选中指标卡 + 表单构建完整载荷", () => {
    const form: ListingForm = { metricType: "ATOMIC", description: "核心营收", freshnessInfo: "T+1" }
    expect(buildListingPayload(card, form)).toEqual({
      metricId: 7,
      metricType: "ATOMIC",
      metricCode: "gmv_daily",
      description: "核心营收",
      freshnessInfo: "T+1",
    })
  })

  it("metricType 缺省为 ATOMIC", () => {
    const out = buildListingPayload(card, { metricType: "", description: "", freshnessInfo: "" })
    expect(out?.metricType).toBe("ATOMIC")
  })

  it("description/freshnessInfo 空白裁剪后省略", () => {
    const out = buildListingPayload(card, { metricType: "DERIVED", description: "  ", freshnessInfo: "" })
    expect(out).toEqual({ metricId: 7, metricType: "DERIVED", metricCode: "gmv_daily" })
    expect(out && "description" in out).toBe(false)
    expect(out && "freshnessInfo" in out).toBe(false)
  })

  it("未选指标卡（null）→ 返回 null（metricId 必填）", () => {
    expect(buildListingPayload(null, { metricType: "ATOMIC", description: "", freshnessInfo: "" })).toBeNull()
  })

  it("metricCode 取自卡片 code", () => {
    const out = buildListingPayload({ ...card, code: "dau" }, { metricType: "ATOMIC", description: "", freshnessInfo: "" })
    expect(out?.metricCode).toBe("dau")
  })
})
