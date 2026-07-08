import { describe, expect, it } from "vitest"
import type { FC } from "react"

import { CONFIG_SECTIONS, filterVisibleSections, type ConfigSection } from "./config-sections"

describe("config-sections registry", () => {
  it("section ids are unique", () => {
    const ids = CONFIG_SECTIONS.map((s) => s.id)
    expect(new Set(ids).size).toBe(ids.length)
  })

  it("ai-agent is the first section with the expected titleKey", () => {
    expect(CONFIG_SECTIONS[0]?.id).toBe("ai-agent")
    expect(CONFIG_SECTIONS[0]?.titleKey).toBe("settingsView.configSectionAiAgent")
  })

  it("every section has icon + component", () => {
    for (const s of CONFIG_SECTIONS) {
      expect(s.icon).toBeTruthy()
      expect(typeof s.component).toBe("function")
    }
  })

  it("filterVisibleSections returns all sections when none require a permission", () => {
    // ai-agent 无 requirePermission → 空 permission 集也全部可见
    expect(filterVisibleSections(CONFIG_SECTIONS, new Set())).toEqual(CONFIG_SECTIONS)
  })

  it("filterVisibleSections hides sections whose requirePermission is absent", () => {
    const Noop = (() => null) as FC
    const withFuture: ConfigSection[] = [
      ...CONFIG_SECTIONS,
      {
        id: "future",
        titleKey: "x",
        icon: CONFIG_SECTIONS[0]!.icon,
        requirePermission: "x:manage",
        component: Noop,
      },
    ]
    const visible = filterVisibleSections(withFuture, new Set(["other"]))
    expect(visible.map((s) => s.id)).toEqual(CONFIG_SECTIONS.map((s) => s.id))
    expect(visible.map((s) => s.id)).not.toContain("future")
  })

  it("filterVisibleSections shows gated sections when the permission is present", () => {
    const Noop = (() => null) as FC
    const gated: ConfigSection[] = [
      {
        id: "gated",
        titleKey: "g",
        icon: CONFIG_SECTIONS[0]!.icon,
        requirePermission: "x:manage",
        component: Noop,
      },
    ]
    expect(filterVisibleSections(gated, new Set(["x:manage"])).map((s) => s.id)).toEqual(["gated"])
  })
})
