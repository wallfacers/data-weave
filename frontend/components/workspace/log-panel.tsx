"use client"

import { useCallback, useLayoutEffect, useState } from "react"
import { motion, useMotionValue, useTransform } from "motion/react"
import { HugeiconsIcon } from "@hugeicons/react"
import { RefreshIcon } from "@hugeicons/core-free-icons"

import { useLogPanelStore, type LogTab } from "@/lib/workspace/log-panel-store"
import { useEventSource } from "@/lib/workspace/use-event-source"
import { Badge } from "@/components/ui/badge"
import { DwScroll } from "@/components/ui/dw-scroll"
import { TabStrip, type TabStripItem } from "@/components/ui/tab-strip"
import { cn } from "@/lib/utils"
import { API_BASE } from "@/lib/types"

const LOG_PANEL_DEFAULT_HEIGHT = 240
const LOG_PANEL_MIN_HEIGHT = 120
const LOG_PANEL_MAX_HEIGHT = 600
const LOG_PANEL_HEIGHT_KEY = "dw.logPanel.height"

/**
 * 底部日志面板：多卡片 tab + 水平拖拽分割线。
 */
export function WorkspaceLogPanel() {
  const { tabs, activeTabId, expanded, close, closeOthers, closeRight, closeLeft, closeAll, activate } =
    useLogPanelStore()

  // ── 高度拖拽 ───────────────────────────────────────────────
  const [, setHeight] = useState(LOG_PANEL_DEFAULT_HEIGHT)
  const heightMotion = useMotionValue(LOG_PANEL_DEFAULT_HEIGHT)
  const [hydrated, setHydrated] = useState(false)

  useLayoutEffect(() => {
    const saved = Number(localStorage.getItem(LOG_PANEL_HEIGHT_KEY))
    if (saved >= LOG_PANEL_MIN_HEIGHT && saved <= LOG_PANEL_MAX_HEIGHT) {
      setHeight(saved)
      heightMotion.set(saved)
    }
    setHydrated(true)
  }, [heightMotion])

  const heightStyle = useTransform(heightMotion, (v) => `${Math.round(v)}px`)

  const onResizeDown = useCallback(
    (e: React.PointerEvent) => {
      e.preventDefault()
      const startY = e.clientY
      const startH = heightMotion.get()
      let current = startH
      const onMove = (ev: PointerEvent) => {
        current = Math.min(
          LOG_PANEL_MAX_HEIGHT,
          Math.max(LOG_PANEL_MIN_HEIGHT, startH + (startY - ev.clientY)),
        )
        heightMotion.set(current)
      }
      const onUp = () => {
        window.removeEventListener("pointermove", onMove)
        window.removeEventListener("pointerup", onUp)
        document.body.style.cursor = ""
        document.body.style.userSelect = ""
        setHeight(current)
        localStorage.setItem(LOG_PANEL_HEIGHT_KEY, String(current))
      }
      document.body.style.cursor = "row-resize"
      document.body.style.userSelect = "none"
      window.addEventListener("pointermove", onMove)
      window.addEventListener("pointerup", onUp)
    },
    [heightMotion],
  )

  if (tabs.length === 0 || !expanded) return null

  return (
    <>
      {/* ── 水平拖拽分割线 ────────────────────────────────────── */}
      <div
        onPointerDown={onResizeDown}
        role="separator"
        aria-orientation="horizontal"
        aria-label="拖拽调整日志面板高度"
        className="group/resize relative z-20 flex h-2 w-full shrink-0 cursor-row-resize touch-none items-center justify-center"
      >
        <div className="h-0.5 w-12 rounded-full bg-border/0 transition-colors group-hover/resize:bg-border" />
      </div>

      {/* ── 日志面板卡片（浅色，与白色主题一致）──────────────────── */}
      <motion.div
        className="mx-3 mb-3 flex shrink-0 flex-col overflow-hidden rounded-lg border bg-card shadow-lg"
        style={{ height: hydrated ? heightStyle : `${LOG_PANEL_DEFAULT_HEIGHT}px` }}
      >
        {/* Tab 条（Chrome 卡片风格，统一 TabStrip；surface=card 使激活标签融入白色内容区） */}
        <TabStrip
          size="sm"
          className="shrink-0 rounded-t-lg"
          surface="card"
          tabs={tabs.map<TabStripItem>((tab) => ({
            id: tab.id,
            label: `${tab.instanceId.slice(0, 8)}…`,
            monospace: true,
          }))}
          activeId={activeTabId}
          onActivate={activate}
          onClose={close}
          onCloseOthers={closeOthers}
          onCloseRight={closeRight}
          onCloseLeft={closeLeft}
          onCloseAll={closeAll}
        />

        {/* Tab 内容（keep-alive，SSE 在此层管理） */}
        <div className="flex min-h-0 flex-1 flex-col bg-card">
          {tabs.map((tab) => (
            <LogTabContent key={tab.id} tab={tab} active={tab.id === activeTabId} />
          ))}
        </div>
      </motion.div>
    </>
  )
}

/**
 * 单个 tab 的日志内容：独立 SSE 连接 + 状态徽章。
 */
function LogTabContent({ tab, active }: { tab: LogTab; active: boolean }) {
  const { events, connected, error, clearEvents } = useEventSource(
    active ? `${API_BASE}/api/ops/instances/${tab.instanceId}/logs/stream` : "",
  )

  const logLines = events.filter((e) => e.type === "log").map((e) => e.data)
  const isEnded = events.some((e) => e.type === "end")

  return (
    <div className={cn("flex flex-1 flex-col overflow-hidden", active ? "flex" : "hidden")}>
      {/* 状态栏：实例 ID + 连接状态 + 刷新 */}
      <div className="flex h-6 shrink-0 items-center gap-2 px-3 text-[10px] text-muted-foreground">
        <span className="font-mono">{tab.instanceId.slice(0, 13)}…</span>
        <Badge
          variant={connected ? "success" : error ? "destructive" : "info"}
          className="h-3.5 px-1 text-[9px]"
        >
          {connected ? "实时" : isEnded ? "已结束" : error ? "断开" : "连接中"}
        </Badge>
        <div className="flex-1" />
        <button
          onClick={clearEvents}
          className="rounded p-0.5 text-muted-foreground hover:text-foreground"
          title="清空"
        >
          <HugeiconsIcon icon={RefreshIcon} className="size-3" />
        </button>
      </div>

      {/* 日志内容 */}
      <DwScroll className="flex-1" innerClassName="px-3 pb-2 font-mono text-xs leading-relaxed">
        {logLines.length === 0 ? (
          <div className="text-muted-foreground">
            {connected ? "等待日志输出…" : isEnded ? "无日志记录" : "连接中…"}
          </div>
        ) : (
          <div className="space-y-px">
            {logLines.map((line, i) => (
              <div key={i} className="whitespace-pre-wrap break-all">
                {line}
              </div>
            ))}
          </div>
        )}
      </DwScroll>
    </div>
  )
}
