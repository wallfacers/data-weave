"use client"

import { useCallback, useEffect, useLayoutEffect, useMemo, useState } from "react"
import { useTranslations } from "next-intl"
import { motion, useMotionValue, useTransform } from "motion/react"
import { HugeiconsIcon } from "@hugeicons/react"
import { RefreshIcon } from "@hugeicons/core-free-icons"

import { useLogPanelStore, type LogTab } from "@/lib/workspace/log-panel-store"
import { useEventSource } from "@/lib/workspace/use-event-source"
import { useApi } from "@/lib/workspace/use-api"
import { Badge } from "@/components/ui/badge"
import { DwScroll } from "@/components/ui/dw-scroll"
import { TabStrip, type TabStripItem } from "@/components/ui/tab-strip"
import { cn } from "@/lib/utils"
import { API_BASE, type Page, type InstanceRow } from "@/lib/types"

/** tab 连接状态：实时 / 已结束 / 断开 / 连接中。 */
type TabConnStatus = "live" | "ended" | "error" | "connecting"

/** 状态圆点（复用语义状态色，neutral 主题安全）。 */
function StatusDot({ status }: { status: TabConnStatus }) {
  const color =
    status === "live"
      ? "bg-success"
      : status === "error"
        ? "bg-destructive"
        : status === "ended"
          ? "bg-muted-foreground/40"
          : "bg-warning"
  return (
    <span
      aria-hidden
      className={cn("size-1.5 rounded-full", color, status === "live" && "animate-pulse")}
    />
  )
}

/** UUIDv7 时间戳前缀重复、辨识度低 —— 取末 8 位 hex 作短 ID。 */
function shortInstanceId(instanceId: string): string {
  const hex = instanceId.replace(/-/g, "")
  return hex.length > 8 ? hex.slice(-8) : hex
}

/** tab 标题：任务名 ·短ID（无任务名退化为「任务 #id」，再退化为纯短 ID）。 */
function tabLabel(
  tab: LogTab,
  taskNames: Map<number, string>,
  t: (k: string, v?: Record<string, string | number | Date>) => string,
): string {
  const sid = shortInstanceId(tab.instanceId)
  const taskId = tab.meta?.taskId
  if (taskId != null) {
    const name = taskNames.get(taskId)
    return name ? `${name} ·${sid}` : `${t("taskFallback", { id: taskId })} ·${sid}`
  }
  return sid
}

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
  const t = useTranslations("logPanel")

  // ── 任务名映射（一次拉全量实例，taskDefId → taskDefName）─────────────────
  const { data } = useApi<Page<InstanceRow>>("/api/ops/instances")
  const taskNames = useMemo(() => {
    const m = new Map<number, string>()
    for (const r of data?.items ?? []) m.set(r.taskDefId, r.taskDefName)
    return m
  }, [data])

  // ── 各 tab 连接状态（LogTabContent 上报，驱动 tab 圆点）──────
  const [statusMap, setStatusMap] = useState<Record<string, TabConnStatus>>({})
  const reportStatus = useCallback((id: string, status: TabConnStatus) => {
    setStatusMap((prev) => (prev[id] === status ? prev : { ...prev, [id]: status }))
  }, [])

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
        aria-label={t("resizeHandle")}
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
            label: tabLabel(tab, taskNames, t),
            monospace: true,
            indicator: <StatusDot status={statusMap[tab.id] ?? "connecting"} />,
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
            <LogTabContent
              key={tab.id}
              tab={tab}
              active={tab.id === activeTabId}
              onStatus={reportStatus}
            />
          ))}
        </div>
      </motion.div>
    </>
  )
}

/**
 * 单个 tab 的日志内容：独立 SSE 连接 + 状态徽章。
 */
function LogTabContent({
  tab,
  active,
  onStatus,
}: {
  tab: LogTab
  active: boolean
  onStatus: (id: string, status: TabConnStatus) => void
}) {
  const t = useTranslations("logPanel")
  // 全部 tab 常驻 SSE 连接（切走也实时），供 tab 圆点显示状态
  const { events, connected, error, clearEvents } = useEventSource(
    `${API_BASE}/api/ops/instances/${tab.instanceId}/logs/stream`,
  )

  const logLines = events.filter((e) => e.type === "log").map((e) => e.data)
  const isEnded = events.some((e) => e.type === "end")
  const status: TabConnStatus = connected
    ? "live"
    : isEnded
      ? "ended"
      : error
        ? "error"
        : "connecting"

  // 上报连接状态给面板（驱动 tab 圆点）
  useEffect(() => {
    onStatus(tab.id, status)
  }, [tab.id, status, onStatus])

  return (
    <div className={cn("flex flex-1 flex-col overflow-hidden", active ? "flex" : "hidden")}>
      {/* 状态栏：实例 ID + 连接状态 + 刷新 */}
      <div className="flex h-6 shrink-0 items-center gap-2 px-3 text-[10px] text-muted-foreground">
        <span className="font-mono">{tab.instanceId.slice(0, 13)}…</span>
        <Badge
          variant={connected ? "success" : error ? "destructive" : "info"}
          className="h-3.5 px-1 text-[9px]"
        >
          {connected ? t("statusLive") : isEnded ? t("statusEnded") : error ? t("statusDisconnected") : t("statusConnecting")}
        </Badge>
        <div className="flex-1" />
        <button
          onClick={clearEvents}
          className="rounded p-0.5 text-muted-foreground hover:text-foreground"
          title={t("clear")}
        >
          <HugeiconsIcon icon={RefreshIcon} className="size-3" />
        </button>
      </div>

      {/* 日志内容 */}
      <DwScroll className="flex-1" innerClassName="px-3 pb-2 font-mono text-xs leading-relaxed">
        {logLines.length === 0 ? (
          <div className="text-muted-foreground">
            {connected ? t("waiting") : isEnded ? t("noRecords") : t("connectingShort")}
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
