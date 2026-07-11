/**
 * 036-D 菜单/视图权限过滤单测（vitest，纯函数，不依赖 React/render）。
 *
 * 验证三角色（ADMIN/DEVELOPER/VIEWER）下左侧导航可见入口的差异（SC-004 角色矩阵一致），
 * 以及切项目重算时菜单随权限收敛（FR-043）。权限集对齐 seed role_permission 矩阵。
 */
import { describe, it, expect } from "vitest"

import {
  NAV_GROUPS,
  NAV_ENTRY_VIEWS,
  filterVisibleItems,
  viewRequiredPermission,
} from "./nav-groups"
import type { ViewType } from "./views"

// 对齐 seed role_permission 矩阵：ADMIN 5 权 / DEVELOPER 4 权 / VIEWER 无
const ADMIN = new Set([
  "task:manage",
  "workflow:manage",
  "metric:manage",
  "datasource:manage",
  "project:manage",
])
const DEVELOPER = new Set([
  "task:manage",
  "workflow:manage",
  "metric:manage",
  "datasource:manage",
])
const VIEWER = new Set<string>()

/** 模拟左侧导航：全分组按权限过滤后的可见入口视图集合。 */
function visibleEntries(perms: ReadonlySet<string>): Set<ViewType> {
  return new Set(NAV_GROUPS.flatMap((g) => filterVisibleItems(g.items, perms)))
}

describe("036-D viewRequiredPermission", () => {
  it("写视图挂对应权限码，只读视图无门槛", () => {
    expect(viewRequiredPermission("workflow-canvas")).toBe("workflow:manage")
    expect(viewRequiredPermission("datasources")).toBe("datasource:manage")
    expect(viewRequiredPermission("settings")).toBe("project:manage")
    // 只读视图
    expect(viewRequiredPermission("ops")).toBeUndefined()
    expect(viewRequiredPermission("metrics")).toBeUndefined()
    expect(viewRequiredPermission("lineage")).toBeUndefined()
    expect(viewRequiredPermission("alerts")).toBeUndefined()
  })
})

describe("036-D 菜单权限过滤（三角色矩阵）", () => {
  it("ADMIN 看到全部入口视图（含 settings）", () => {
    const v = visibleEntries(ADMIN)
    expect(v.size).toBe(NAV_ENTRY_VIEWS.size)
    expect(v.has("settings")).toBe(true)
    expect(v.has("workflow-canvas")).toBe(true)
    expect(v.has("datasources")).toBe(true)
  })

  it("DEVELOPER 看到开发写视图，但不见 settings（无 project:manage）", () => {
    const v = visibleEntries(DEVELOPER)
    expect(v.has("workflow-canvas")).toBe(true)
    expect(v.has("datasources")).toBe(true)
    expect(v.has("settings")).toBe(false)
  })

  it("VIEWER 只看到只读视图（所有写视图入口隐藏，US4 AC1）", () => {
    const v = visibleEntries(VIEWER)
    // 只读视图可见
    for (const ro of [
      "ops",
      "metrics",
      "freshness",
      "fleet",
      "lineage",
      "alerts",
    ] as ViewType[]) {
      expect(v.has(ro)).toBe(true)
    }
    // 写视图不可见
    expect(v.has("workflow-canvas")).toBe(false)
    expect(v.has("datasources")).toBe(false)
    expect(v.has("settings")).toBe(false)
  })

  it("无权限集（空 Set）等效 VIEWER，保守只读", () => {
    expect(visibleEntries(new Set<string>()).has("workflow-canvas")).toBe(false)
    expect(visibleEntries(new Set<string>()).has("ops")).toBe(true)
  })
})

describe("036-D 切项目重算（FR-043）", () => {
  it("权限随项目变化：DEVELOPER→VIEWER 菜单收敛", () => {
    // 同一用户在 A 项目是 DEVELOPER，切到 B 项目是 VIEWER
    expect(visibleEntries(DEVELOPER).has("workflow-canvas")).toBe(true)
    expect(visibleEntries(VIEWER).has("workflow-canvas")).toBe(false)
    // 收敛后 settings 始终不可见（VIEWER/DEVELOPER 均无 project:manage 在 VIEWER 侧）
    expect(visibleEntries(VIEWER).has("settings")).toBe(false)
  })

  it("权限随项目变化：VIEWER→ADMIN 菜单扩展（含 settings）", () => {
    expect(visibleEntries(VIEWER).has("settings")).toBe(false)
    expect(visibleEntries(ADMIN).has("settings")).toBe(true)
  })
})
