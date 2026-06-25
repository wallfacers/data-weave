import { describe, it, expect } from "vitest"
import {
  type FilterDef,
  type FilterValues,
  emptyValueFor,
  isFilterActive,
  countActiveFilters,
  initialFilterValues,
  toQueryParams,
  matchesFilter,
  applyClientFilters,
  paginate,
  totalPagesOf,
} from "./data-table"

interface Row {
  id: string
  name: string
  status: string
  type: string
  bizDate: string
  draft: boolean
}

const ROWS: Row[] = [
  { id: "1", name: "daily_orders", status: "ONLINE", type: "SQL", bizDate: "2026-06-20", draft: false },
  { id: "2", name: "hourly_clicks", status: "DRAFT", type: "PYTHON", bizDate: "2026-06-22", draft: true },
  { id: "3", name: "orders_refund", status: "ONLINE", type: "SQL", bizDate: "2026-06-25", draft: true },
]

const DEFS: FilterDef[] = [
  { key: "name", label: "名称", kind: "search", matchKeys: ["name"] },
  { key: "status", label: "状态", kind: "segmented", options: [{ value: "ONLINE", label: "上线" }] },
  { key: "type", label: "类型", kind: "multiSelect", options: [{ value: "SQL", label: "SQL" }] },
  { key: "bizDate", label: "业务日期", kind: "dateRange" },
  { key: "draft", label: "草稿", kind: "toggle" },
]

describe("emptyValueFor / isFilterActive", () => {
  it("returns the right empty value per kind", () => {
    expect(emptyValueFor("search")).toBe("")
    expect(emptyValueFor("segmented")).toBe("")
    expect(emptyValueFor("multiSelect")).toEqual([])
    expect(emptyValueFor("dateRange")).toEqual({})
    expect(emptyValueFor("toggle")).toBe(false)
  })

  it("detects active vs empty", () => {
    expect(isFilterActive("search", "")).toBe(false)
    expect(isFilterActive("search", "x")).toBe(true)
    expect(isFilterActive("multiSelect", [])).toBe(false)
    expect(isFilterActive("multiSelect", ["SQL"])).toBe(true)
    expect(isFilterActive("dateRange", {})).toBe(false)
    expect(isFilterActive("dateRange", { from: "2026-06-20" })).toBe(true)
    expect(isFilterActive("toggle", false)).toBe(false)
    expect(isFilterActive("toggle", true)).toBe(true)
  })
})

describe("countActiveFilters / initialFilterValues", () => {
  it("counts only active filters", () => {
    const values: FilterValues = { name: "ord", status: "", type: ["SQL"], bizDate: {}, draft: false }
    expect(countActiveFilters(DEFS, values)).toBe(2)
  })

  it("builds empty initial values merged with defaults", () => {
    const v = initialFilterValues(DEFS, { status: "ONLINE" })
    expect(v).toEqual({ name: "", status: "ONLINE", type: [], bizDate: {}, draft: false })
  })
})

describe("toQueryParams (server mode)", () => {
  it("flattens active filters + paging into query string", () => {
    const values: FilterValues = {
      name: "ord",
      status: "ONLINE",
      type: ["SQL", "PYTHON"],
      bizDate: { from: "2026-06-01", to: "2026-06-25" },
      draft: true,
    }
    const qs = toQueryParams({ filters: values, page: 2, size: 20 }, DEFS)
    expect(qs.get("name")).toBe("ord")
    expect(qs.get("status")).toBe("ONLINE")
    expect(qs.get("type")).toBe("SQL,PYTHON")
    expect(qs.get("bizDateFrom")).toBe("2026-06-01")
    expect(qs.get("bizDateTo")).toBe("2026-06-25")
    expect(qs.get("draft")).toBe("true")
    expect(qs.get("page")).toBe("2")
    expect(qs.get("size")).toBe("20")
  })

  it("omits inactive filters", () => {
    const qs = toQueryParams({ filters: initialFilterValues(DEFS), page: 1, size: 10 }, DEFS)
    expect(qs.has("name")).toBe(false)
    expect(qs.has("type")).toBe(false)
    expect(qs.has("draft")).toBe(false)
    expect(qs.get("page")).toBe("1")
  })
})

describe("matchesFilter / applyClientFilters (client mode)", () => {
  it("search matches substring case-insensitively", () => {
    expect(applyClientFilters(ROWS, DEFS, { name: "ord" }).map((r) => r.id)).toEqual(["1", "3"])
  })

  it("segmented matches exact field", () => {
    expect(applyClientFilters(ROWS, DEFS, { status: "ONLINE" }).map((r) => r.id)).toEqual(["1", "3"])
  })

  it("multiSelect matches any-of", () => {
    expect(applyClientFilters(ROWS, DEFS, { type: ["PYTHON"] }).map((r) => r.id)).toEqual(["2"])
  })

  it("toggle keeps only truthy rows", () => {
    expect(applyClientFilters(ROWS, DEFS, { draft: true }).map((r) => r.id)).toEqual(["2", "3"])
  })

  it("dateRange filters inclusive bounds", () => {
    expect(
      applyClientFilters(ROWS, DEFS, { bizDate: { from: "2026-06-21", to: "2026-06-25" } }).map((r) => r.id),
    ).toEqual(["2", "3"])
  })

  it("combines filters with AND", () => {
    expect(
      applyClientFilters(ROWS, DEFS, { status: "ONLINE", draft: true }).map((r) => r.id),
    ).toEqual(["3"])
  })

  it("inactive filter matches everything", () => {
    expect(matchesFilter(ROWS[0], DEFS[0], "")).toBe(true)
  })
})

describe("paginate / totalPagesOf", () => {
  it("slices the right page", () => {
    const p = paginate(ROWS, 2, 2)
    expect(p.items.map((r) => r.id)).toEqual(["3"])
    expect(p.total).toBe(3)
    expect(p.page).toBe(2)
  })

  it("clamps page into range", () => {
    expect(paginate(ROWS, 99, 2).page).toBe(2)
    expect(paginate(ROWS, 0, 2).page).toBe(1)
  })

  it("totalPagesOf is at least 1", () => {
    expect(totalPagesOf(0, 20)).toBe(1)
    expect(totalPagesOf(41, 20)).toBe(3)
  })
})
