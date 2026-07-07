import { describe, expect, it } from "vitest"
import {
  datasourceColor,
  datasourceAbbr,
  DATASOURCE_UNKNOWN_COLOR,
} from "@/lib/workspace/lineage-datasource-style"

describe("datasourceColor", () => {
  it("同一 id 稳定返回同一 chart token（确定性）", () => {
    expect(datasourceColor("ds-mysql")).toBe(datasourceColor("ds-mysql"))
    expect(datasourceColor("ds-hive")).toBe(datasourceColor("ds-hive"))
  })

  it("返回 chart-1..5 之一（语义 token，禁硬编码色值）", () => {
    expect(datasourceColor("ds-mysql")).toMatch(/^var\(--color-chart-[1-5]\)$/)
    expect(datasourceColor("ds-hive")).toMatch(/^var\(--color-chart-[1-5]\)$/)
  })

  it("不同 id 多次取样覆盖调色板（非单一值，跨库可辨）", () => {
    const samples = new Set(
      ["ds-1", "ds-2", "ds-3", "ds-4", "ds-5", "ds-6", "ds-7", "ds-8"].map(datasourceColor)
    )
    expect(samples.size).toBeGreaterThan(1)
  })

  it("空 / 缺省 → 中性弱化色（孤儿 / 未登记 / METRIC 端）", () => {
    expect(datasourceColor(undefined)).toBe(DATASOURCE_UNKNOWN_COLOR)
    expect(datasourceColor(null)).toBe(DATASOURCE_UNKNOWN_COLOR)
    expect(datasourceColor("")).toBe(DATASOURCE_UNKNOWN_COLOR)
  })
})

describe("datasourceAbbr", () => {
  it("取首个字母数字段前两字符大写", () => {
    expect(datasourceAbbr("mysql-prod")).toBe("MY")
    expect(datasourceAbbr("hive-dw")).toBe("HI")
    expect(datasourceAbbr("pg-bi")).toBe("PG")
    expect(datasourceAbbr("ods_db")).toBe("OD")
  })

  it("空名兜底 ?", () => {
    expect(datasourceAbbr(undefined)).toBe("?")
    expect(datasourceAbbr(null)).toBe("?")
    expect(datasourceAbbr("")).toBe("?")
    expect(datasourceAbbr("   ")).toBe("?")
  })

  it("配色耗尽时仍可由缩写区分同名跨库（FR-011 文本兜底）", () => {
    // 两个不同数据源即便撞色，缩写也不同 → 可辨
    expect(datasourceAbbr("mysql-prod")).not.toBe(datasourceAbbr("hive-dw"))
    expect(datasourceAbbr("pg-bi")).not.toBe(datasourceAbbr("mysql-prod"))
  })
})
