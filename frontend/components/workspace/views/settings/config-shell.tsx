"use client"

/**
 * 057 系统设置「配置」tab 可复用外壳：左侧分区导航 + 右侧内容（数据开发风格）。
 *
 * 复用 DataDevIdeShell（workflow-canvas-view.tsx）的「可拖拽调宽左卡片 + 右圆角卡片内容区」模式：
 * motion value 驱动宽度（拖拽过程零 React 提交）+ localStorage 持久化。
 * 设计约束（DESIGN.md）：rounded-[var(--radius-lg)] border bg-card shadow-lg、DwScroll、
 * --card-spacing 驱动右区留白、无区域分割线（仅卡片自身 border + 拖拽 handle 的 hover 提示条）、语义 token。
 *
 * 自管 activeSectionId（localStorage 记忆上次分区）；左侧 ConfigNav 渲染当前权限可见分区，
 * 右侧渲染选中分区的 component（内联）。
 */
import { useCallback, useLayoutEffect, useState, type PointerEvent as ReactPointerEvent } from "react"
import { useMotionValue, useTransform, motion } from "motion/react"
import { useTranslations } from "next-intl"
import { DwScroll } from "@/components/ui/dw-scroll"
import { ConfigNav } from "./config-nav"
import { CONFIG_SECTIONS, filterVisibleSections } from "@/lib/workspace/settings/config-sections"
import { useProjectPermissions } from "@/lib/project-permissions"

/** 左侧导航卡片宽度：默认 / 可拖拽范围 / 持久化键（对齐 DataDevIdeShell catalog 宽度常量）。 */
const NAV_DEFAULT_WIDTH = 256
const NAV_MIN_WIDTH = 180
const NAV_MAX_WIDTH = 480
const NAV_WIDTH_KEY = "dw.settings.configNavWidth"
/** 上次选中分区持久化键。 */
const ACTIVE_KEY = "dw.settings.configSection"

export function ConfigShell() {
  const t = useTranslations()
  const { membership } = useProjectPermissions()
  const sections = filterVisibleSections(CONFIG_SECTIONS, new Set(membership?.permissions ?? []))

  const [activeId, setActiveId] = useState<string>(
    () => {
      if (typeof window === "undefined") return sections[0]?.id ?? ""
      const saved = window.localStorage.getItem(ACTIVE_KEY) ?? ""
      return sections.some((s) => s.id === saved) ? saved : (sections[0]?.id ?? "")
    },
  )
  const active = sections.find((s) => s.id === activeId) ?? sections[0]

  const onSelect = useCallback((id: string) => {
    setActiveId(id)
    if (typeof window !== "undefined") window.localStorage.setItem(ACTIVE_KEY, id)
  }, [])

  // ── 左卡片可拖拽调宽（motion value，拖拽过程零 React 提交；localStorage 持久化）──
  const navWidthMotion = useMotionValue(NAV_DEFAULT_WIDTH)
  const [hydrated, setHydrated] = useState(false)
  useLayoutEffect(() => {
    const saved = Number(window.localStorage.getItem(NAV_WIDTH_KEY))
    if (saved >= NAV_MIN_WIDTH && saved <= NAV_MAX_WIDTH) navWidthMotion.set(saved)
    setHydrated(true)
  }, [navWidthMotion])
  const navWidthStyle = useTransform(navWidthMotion, (v) => `${Math.round(v)}px`)
  const navWidthProp = hydrated ? navWidthStyle : `var(--dw-config-nav-width, ${NAV_DEFAULT_WIDTH}px)`

  const onResizeDown = useCallback(
    (e: ReactPointerEvent) => {
      e.preventDefault()
      const startX = e.clientX
      const startW = navWidthMotion.get()
      let current = startW
      const onMove = (ev: PointerEvent) => {
        current = Math.min(NAV_MAX_WIDTH, Math.max(NAV_MIN_WIDTH, startW + (ev.clientX - startX)))
        navWidthMotion.set(current)
      }
      const onUp = () => {
        window.removeEventListener("pointermove", onMove)
        window.removeEventListener("pointerup", onUp)
        document.body.style.cursor = ""
        document.body.style.userSelect = ""
        window.localStorage.setItem(NAV_WIDTH_KEY, String(current))
      }
      document.body.style.cursor = "col-resize"
      document.body.style.userSelect = "none"
      window.addEventListener("pointermove", onMove)
      window.addEventListener("pointerup", onUp)
    },
    [navWidthMotion],
  )

  if (!active) {
    return (
      <div className="flex h-full items-center justify-center text-sm text-muted-foreground">
        {t("settingsView.configEmpty")}
      </div>
    )
  }
  const ActiveComponent = active.component

  return (
    <div className="flex h-full gap-3 p-3">
      {/* 左：配置分区导航（可拖拽调宽卡片） */}
      <div className="relative flex shrink-0 flex-col pr-1.5">
        <motion.div
          className="flex h-full flex-col overflow-hidden rounded-[var(--radius-lg)] border bg-card shadow-lg"
          style={{ width: navWidthProp }}
        >
          <ConfigNav sections={sections} activeId={active.id} onSelect={onSelect} />
        </motion.div>
        {/* 右缘拖拽分隔（col-resize；hover 显提示条，无长留分割线） */}
        <div
          onPointerDown={onResizeDown}
          role="separator"
          aria-orientation="vertical"
          aria-label={t("settingsView.configResizeNav")}
          className="group/resize absolute inset-y-3 right-0 z-20 flex w-2 cursor-col-resize touch-none items-center justify-center"
        >
          <div className="h-12 w-0.5 rounded-full bg-border/0 transition-colors group-hover/resize:bg-border" />
        </div>
      </div>

      {/* 右：选中分区内容（圆角卡片，DwScroll + --card-spacing 留白） */}
      <div className="flex min-w-0 flex-1 flex-col overflow-hidden rounded-[var(--radius-lg)] border bg-card shadow-lg">
        <DwScroll className="flex-1" innerClassName="overflow-y-auto">
          <div className="p-(--card-spacing)">
            <ActiveComponent />
          </div>
        </DwScroll>
      </div>
    </div>
  )
}
