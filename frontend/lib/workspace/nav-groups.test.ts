import { describe, it, expect } from "vitest"

import {
  NAV_GROUPS,
  NAV_ENTRY_VIEWS,
  CONTEXT_DETAIL_VIEWS,
  viewToGroup,
  resolveActiveHighlight,
  classifiedViews,
  ALL_VIEWS,
} from "./nav-groups"
import { VIEW_META, type ViewType } from "./views"

describe("nav-groups 覆盖不变量", () => {
  it("入口视图 ∪ 上下文详情视图 == VIEW_META 全集（无遗漏，SC-003）", () => {
    const classified = classifiedViews()
    expect(classified.size).toBe(ALL_VIEWS.length)
    for (const v of ALL_VIEWS) expect(classified.has(v)).toBe(true)
  })

  it("入口视图无重复（无重复入口，SC-003）", () => {
    const flat = NAV_GROUPS.flatMap((g) => g.items)
    expect(flat.length).toBe(new Set(flat).size)
    expect(flat.length).toBe(NAV_ENTRY_VIEWS.size)
  })

  it("入口与详情互斥（详情视图不作独立入口，FR-007）", () => {
    for (const v of CONTEXT_DETAIL_VIEWS) expect(NAV_ENTRY_VIEWS.has(v)).toBe(false)
  })

  it("每个入口视图都已注册于 VIEW_META", () => {
    for (const v of NAV_ENTRY_VIEWS) expect(v in VIEW_META).toBe(true)
  })

  it("viewToGroup 对每个入口视图都有归属，且组存在", () => {
    const groupIds = new Set(NAV_GROUPS.map((g) => g.id))
    for (const v of NAV_ENTRY_VIEWS) {
      const g = viewToGroup[v]
      expect(g).toBeTruthy()
      expect(groupIds.has(g as string)).toBe(true)
    }
  })

  it("分组顺序稳定（FR-005）", () => {
    expect(NAV_GROUPS.map((g) => g.id)).toEqual([
      "dev",
      "ops",
      "alerting",
      "governance",
      "assets",
      "admin",
    ])
  })
})

describe("resolveActiveHighlight 高亮映射（FR-006/FR-007）", () => {
  it("入口视图 → 高亮自身 + 所属分组", () => {
    expect(resolveActiveHighlight("ops")).toEqual({ view: "ops", group: "ops" })
    expect(resolveActiveHighlight("datasources")).toEqual({ view: "datasources", group: "assets" })
  })

  it("上下文详情视图 → 归运维监控模块，无具体高亮项", () => {
    expect(resolveActiveHighlight("instance-log")).toEqual({ view: undefined, group: "ops" })
    expect(resolveActiveHighlight("workflow-instance-detail")).toEqual({
      view: undefined,
      group: "ops",
    })
  })

  it("未知/未定义 → 无高亮", () => {
    expect(resolveActiveHighlight(undefined)).toEqual({})
    expect(resolveActiveHighlight("nope" as ViewType)).toEqual({})
  })
})
