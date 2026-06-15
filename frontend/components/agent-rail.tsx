"use client"

import { useCallback, useLayoutEffect, useState } from "react"
import { motion, useMotionValue, useTransform } from "motion/react"
import { HugeiconsIcon } from "@hugeicons/react"
import { SparklesIcon, Logout01Icon } from "@hugeicons/core-free-icons"

import { AgentChat, type AgentPageContext } from "@/components/agent-chat"
import { SettingsTrigger } from "@/components/settings-sheet"
import { useAuth } from "@/lib/auth"
import { useWorkspaceStore } from "@/lib/workspace/store"
import { VIEW_META } from "@/lib/workspace/views"

/** 长 ID（UUID）紧凑显示：含连字符的取后 6 位 hex，与日志面板 tab 短 ID 一致；短 ID 原样。 */
function compactId(id: string): string {
  const hex = id.replace(/-/g, "")
  return hex.length > 8 ? hex.slice(-6) : id
}

/** 左栏对话主驾宽度：默认 / 可拖拽范围 / 持久化键 */
const RAIL_DEFAULT_WIDTH = 440
const RAIL_MIN_WIDTH = 340
const RAIL_MAX_WIDTH = 680
const RAIL_WIDTH_KEY = "dw.agentRail.width"

/**
 * Agent 对话主驾：左栏常驻（不收起），右缘可拖拽调宽。
 * 上下文感知来源 = workspace 激活 tab（视图 + params），随对话送达后端。
 */
export function AgentRail() {
  // ── 面板宽度（可拖拽分割线调整，localStorage 持久化）────────────
  // 宽度用 motion value 驱动，拖拽过程中不触发 React 渲染；
  // setRailWidth 仅作下次拖拽的 startW 基准。
  const [, setRailWidth] = useState(RAIL_DEFAULT_WIDTH)
  const railWidthMotion = useMotionValue(RAIL_DEFAULT_WIDTH)
  // hydrated 后切换到 motion value 驱动（之前由 CSS 变量撑住宽度）。
  const [hydrated, setHydrated] = useState(false)
  useLayoutEffect(() => {
    const saved = Number(localStorage.getItem(RAIL_WIDTH_KEY))
    if (saved >= RAIL_MIN_WIDTH && saved <= RAIL_MAX_WIDTH) {
      setRailWidth(saved)
      railWidthMotion.set(saved)
    }
    setHydrated(true)
  }, [railWidthMotion])
  const railWidthStyle = useTransform(railWidthMotion, (v) => `${Math.round(v)}px`)

  // 两阶段宽度策略：
  //   SSR / hydration 阶段 → CSS 变量（由 layout 阻塞脚本从 localStorage 设置），
  //                          首帧即为正确宽度，无闪动；
  //   hydrated 后          → Framer Motion motion value 接管，支持拖拽。
  const widthStyle = hydrated
    ? railWidthStyle
    : "var(--dw-rail-width, 440px)"

  // 右缘分割线拖拽：全程走 motion value（面板在左，指针右移 → 变宽），
  // 松手时才写 React state + localStorage。
  const onResizeDown = useCallback(
    (e: React.PointerEvent) => {
      e.preventDefault()
      const startX = e.clientX
      const startW = railWidthMotion.get()
      let current = startW
      const onMove = (ev: PointerEvent) => {
        current = Math.min(
          RAIL_MAX_WIDTH,
          Math.max(RAIL_MIN_WIDTH, startW + (ev.clientX - startX)),
        )
        railWidthMotion.set(current)
      }
      const onUp = () => {
        window.removeEventListener("pointermove", onMove)
        window.removeEventListener("pointerup", onUp)
        document.body.style.cursor = ""
        document.body.style.userSelect = ""
        setRailWidth(current)
        localStorage.setItem(RAIL_WIDTH_KEY, String(current))
      }
      document.body.style.cursor = "col-resize"
      document.body.style.userSelect = "none"
      window.addEventListener("pointermove", onMove)
      window.addEventListener("pointerup", onUp)
    },
    [railWidthMotion],
  )

  // 双向上下文：workspace 激活 tab → 逐消息页面上下文（经 forwardedProps 送达后端）。
  const { user, logout } = useAuth()

  const activeTab = useWorkspaceStore((s) =>
    s.tabs.find((t) => t.id === s.activeTabId),
  )
  const params = activeTab?.params
  const asStr = (v: unknown) => (v != null ? String(v) : undefined)
  const pageContext: AgentPageContext = {
    module: activeTab?.view,
    pathname: "/",
    taskId: asStr(params?.taskId ?? params?.highlightTaskId),
    instanceId: asStr(params?.instanceId),
    nodeId: asStr(params?.nodeId),
  }
  const moduleName = activeTab ? VIEW_META[activeTab.view].title : ""
  const rawContext =
    pageContext.instanceId ?? pageContext.nodeId ?? pageContext.taskId ?? null
  const contextObject = rawContext ? compactId(rawContext) : null

  return (
    <motion.div
      className="relative flex shrink-0 flex-col p-3 pr-1.5"
      style={{ width: widthStyle }}
    >
      <div className="flex min-h-0 flex-1 flex-col overflow-hidden rounded-[var(--radius-lg)] border bg-sidebar shadow-lg">
        {/* 标题行：品牌 + 当前上下文 + 设置（无下边框，遵守无分割线规则） */}
        <div className="flex h-14 shrink-0 items-center gap-2 px-4">
          <div className="flex size-7 items-center justify-center rounded-lg bg-primary text-primary-foreground">
            <HugeiconsIcon icon={SparklesIcon} className="size-4" />
          </div>
          <span className="text-sm font-semibold">DataWeave</span>
          {moduleName && (
            <span
              className="min-w-0 truncate text-xs text-muted-foreground"
              title={rawContext ? `${moduleName} · ${rawContext}` : moduleName}
            >
              当前：{moduleName}
              {contextObject && (
                <>
                  {" · "}
                  <span className="font-mono">{contextObject}</span>
                </>
              )}
            </span>
          )}
          <div className="flex-1" />
          {user && (
            <span className="text-xs text-muted-foreground">{user.displayName}</span>
          )}
          <button
            onClick={logout}
            title="退出登录"
            className="flex size-7 items-center justify-center rounded-lg text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
          >
            <HugeiconsIcon icon={Logout01Icon} className="size-4" />
          </button>
          <SettingsTrigger />
        </div>

        {/* Agent 对话区 */}
        <div className="flex min-h-0 flex-1 flex-col overflow-hidden">
          <AgentChat context={pageContext} />
        </div>
      </div>

      {/* 拖拽分割线：右缘透明命中区，hover 显一条细线；按下拖动改面板宽度 */}
      <div
        onPointerDown={onResizeDown}
        role="separator"
        aria-orientation="vertical"
        aria-label="拖拽调整 Agent 面板宽度"
        className="group/resize absolute inset-y-3 right-0 z-20 flex w-2 cursor-col-resize touch-none items-center justify-center"
      >
        <div className="h-12 w-0.5 rounded-full bg-border/0 transition-colors group-hover/resize:bg-border" />
      </div>
    </motion.div>
  )
}
