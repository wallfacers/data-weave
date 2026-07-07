"use client"

/**
 * 运行日志 Tabs 公共组件（task-run-decouple 抽出，供任务编辑器与工作流画布共用）。
 *
 * - `RunTab`：一次运行的日志页（kind=result 为结果集占位，本期不渲染行数据）。
 * - `useRunLogTabs`：日志 Tabs 状态 hook（打开/关闭族操作 + 可拖拽高度持久化），两处共用，避免漂移。
 * - `RunLogsTabs`：Tabs 容器，每页常驻挂载（隐藏非激活）以保各自 SSE 连接不因切换而断。
 * - `LogTab`：单实例日志，订阅 logs/stream，DataWorks 风滚屏（自动滚底，banner 行弱化着色）。
 */
import { useCallback, useEffect, useRef, useState } from "react"
import { useTranslations } from "next-intl"
import { DocumentCodeIcon } from "@hugeicons/core-free-icons"
import { OverlayScrollbarsComponent, type OverlayScrollbarsComponentRef } from "overlayscrollbars-react"
import "overlayscrollbars/overlayscrollbars.css"

import { API_BASE } from "@/lib/types"
import { useFormatDateTime } from "@/hooks/use-format-date-time"
import { TabStrip, type TabStripItem } from "@/components/ui/tab-strip"
import { useEventSource } from "@/lib/workspace/use-event-source"
import { deriveRunDotState, parseEndState, runDotColor, type RunDotState } from "@/lib/workspace/run-dot-state"
import { LoadingState } from "@/components/workspace/shared/loading-state"
import { cn } from "@/lib/utils"

const LOG_HEIGHT_DEFAULT = 224
const LOG_HEIGHT_MIN = 80
const LOG_HEIGHT_MAX = 700

/** 一次运行的日志 tab（kind=result 为结果集占位，本期不渲染行数据）。 */
export interface RunTab {
  instanceId: string
  taskName: string
  startedAt: string
  kind: "log" | "result"
}

/**
 * 日志 Tabs 状态 hook：维护 runTabs / 激活页 / 可拖拽高度（localStorage 持久化），
 * 提供 openRunTab（去重 + 激活）与关闭族操作。任务编辑器与画布共用。
 */
export function useRunLogTabs(storageKey: string, defaultHeight = LOG_HEIGHT_DEFAULT) {
  const [runTabs, setRunTabs] = useState<RunTab[]>([])
  const [activeRunTab, setActiveRunTab] = useState<string | null>(null)
  const [logHeight, setLogHeight] = useState(defaultHeight)

  useEffect(() => {
    const saved = Number(localStorage.getItem(storageKey))
    if (saved >= LOG_HEIGHT_MIN && saved <= LOG_HEIGHT_MAX) setLogHeight(saved)
  }, [storageKey])

  // 拖拽分割：分割条在日志区上沿，上拖增高。
  const onLogResizeDown = useCallback(
    (e: React.PointerEvent) => {
      e.preventDefault()
      const startY = e.clientY
      const startH = logHeight
      let current = startH
      const onMove = (ev: PointerEvent) => {
        current = Math.min(LOG_HEIGHT_MAX, Math.max(LOG_HEIGHT_MIN, startH - (ev.clientY - startY)))
        setLogHeight(current)
      }
      const onUp = () => {
        window.removeEventListener("pointermove", onMove)
        window.removeEventListener("pointerup", onUp)
        document.body.style.cursor = ""
        document.body.style.userSelect = ""
        localStorage.setItem(storageKey, String(Math.round(current)))
      }
      document.body.style.cursor = "row-resize"
      document.body.style.userSelect = "none"
      window.addEventListener("pointermove", onMove)
      window.addEventListener("pointerup", onUp)
    },
    [logHeight, storageKey],
  )

  /** 打开（或激活已存在的）日志 tab：以 instanceId 去重，始终激活目标页。 */
  const openRunTab = useCallback((tab: RunTab) => {
    setRunTabs((prev) => (prev.some((tb) => tb.instanceId === tab.instanceId) ? prev : [...prev, tab]))
    setActiveRunTab(tab.instanceId)
  }, [])

  // 关闭族：算出新列表后，若当前激活页已被关掉则改激活末页（空则 null，日志区随之隐藏）。
  const applyRunTabs = useCallback((compute: (prev: RunTab[]) => RunTab[]) => {
    setRunTabs((prev) => {
      const next = compute(prev)
      setActiveRunTab((cur) =>
        cur && next.some((tb) => tb.instanceId === cur)
          ? cur
          : next.length
            ? next[next.length - 1].instanceId
            : null,
      )
      return next
    })
  }, [])

  const closeRunTab = useCallback((id: string) => applyRunTabs((prev) => prev.filter((tb) => tb.instanceId !== id)), [applyRunTabs])
  const closeOtherRunTabs = useCallback((id: string) => applyRunTabs((prev) => prev.filter((tb) => tb.instanceId === id)), [applyRunTabs])
  const closeRunTabsRight = useCallback(
    (id: string) =>
      applyRunTabs((prev) => {
        const i = prev.findIndex((tb) => tb.instanceId === id)
        return i < 0 ? prev : prev.slice(0, i + 1)
      }),
    [applyRunTabs],
  )
  const closeRunTabsLeft = useCallback(
    (id: string) =>
      applyRunTabs((prev) => {
        const i = prev.findIndex((tb) => tb.instanceId === id)
        return i < 0 ? prev : prev.slice(i)
      }),
    [applyRunTabs],
  )
  const closeAllRunTabs = useCallback(() => applyRunTabs(() => []), [applyRunTabs])

  return {
    runTabs,
    activeRunTab,
    setActiveRunTab,
    logHeight,
    onLogResizeDown,
    openRunTab,
    closeRunTab,
    closeOtherRunTabs,
    closeRunTabsRight,
    closeRunTabsLeft,
    closeAllRunTabs,
  }
}

/**
 * 运行日志 Tabs 容器：每个 tab 一条独立实例日志流（命名=任务名/节点名 + 运行时间）。
 * 所有 tab 内容常驻挂载（隐藏非激活），保持各自 SSE 连接不因切换而断；预留结果集 tab 占位。
 */
export function RunLogsTabs({
  tabs,
  activeId,
  onActivate,
  onClose,
  onCloseOthers,
  onCloseRight,
  onCloseLeft,
  onCloseAll,
  onDotChange,
}: {
  tabs: RunTab[]
  activeId: string | null
  onActivate: (id: string) => void
  onClose: (id: string) => void
  onCloseOthers: (id: string) => void
  onCloseRight: (id: string) => void
  onCloseLeft: (id: string) => void
  onCloseAll: () => void
  /** 每 Tab 圆点态聚合上提，供工具栏运行按钮据当前运行实例态切换 Run⇄Stop（可选，向后兼容）。 */
  onDotChange?: (dot: Record<string, RunDotState>) => void
}) {
  const t = useTranslations("taskEditor")
  const ti = useTranslations("instanceTable")
  const formatDateTime = useFormatDateTime()
  const [dot, setDot] = useState<Record<string, RunDotState>>({})
  const active = activeId ?? (tabs.length ? tabs[tabs.length - 1].instanceId : null)

  // dot map 变更上提给父级（用 ref 持有回调，避免内联函数引起的多余 effect）
  const onDotChangeRef = useRef(onDotChange)
  onDotChangeRef.current = onDotChange
  useEffect(() => {
    onDotChangeRef.current?.(dot)
  }, [dot])

  // 圆点 tooltip 文案：复用既有 i18n state 文案（instanceTable.state* + taskEditor.logConnectingShort），无需新增 key。
  // 颜色映射见 run-dot-state.ts 的 runDotColor（语义 token，与 log-panel StatusDot 一致）。
  const dotLabel: Record<RunDotState, string> = {
    running: ti("stateRunning"),
    success: ti("stateSuccess"),
    failed: ti("stateFailed"),
    stopped: ti("stateStopped"),
    connecting: t("logConnectingShort"),
  }

  const stripTabs: TabStripItem[] = tabs.map((tb) => {
    const ds = dot[tb.instanceId] ?? "connecting"
    return {
      id: tb.instanceId,
      label: `${tb.taskName} · ${formatDateTime(tb.startedAt)}`,
      icon: DocumentCodeIcon,
      indicator: (
        <span title={dotLabel[ds]} className={cn("mr-1 size-1.5 rounded-full", runDotColor[ds])} />
      ),
    }
  })

  return (
    <div className="flex h-full flex-col">
      <TabStrip
        tabs={stripTabs}
        activeId={active}
        onActivate={onActivate}
        onClose={onClose}
        onCloseOthers={onCloseOthers}
        onCloseRight={onCloseRight}
        onCloseLeft={onCloseLeft}
        onCloseAll={onCloseAll}
        size="sm"
        surface="background"
      />
      <div className="relative min-h-0 flex-1">
        {tabs.map((tb) => (
          <div
            key={tb.instanceId}
            className={cn("absolute inset-0", tb.instanceId === active ? "block" : "hidden")}
          >
            {tb.kind === "result" ? (
              <div className="flex h-full items-center justify-center text-xs text-muted-foreground">
                {t("logResultPending")}
              </div>
            ) : (
              <LogTab
                instanceId={tb.instanceId}
                onDot={(s) => setDot((m) => (m[tb.instanceId] === s ? m : { ...m, [tb.instanceId]: s }))}
              />
            )}
          </div>
        ))}
      </div>
    </div>
  )
}

/** 单实例日志：订阅 logs/stream，DataWorks 风滚屏（自动滚底，banner 行弱化着色）。滚动条用项目规范的 OverlayScrollbars。 */
function LogTab({ instanceId, onDot }: { instanceId: string; onDot: (s: RunDotState) => void }) {
  const t = useTranslations("taskEditor")
  const osRef = useRef<OverlayScrollbarsComponentRef>(null)
  const autoScroll = useRef(true)
  const { events, connected, error } = useEventSource(
    `${API_BASE}/api/ops/instances/${instanceId}/logs/stream`,
  )
  const lines = events.filter((e) => e.type === "log").map((e) => e.data)
  const endEvent = events.find((e) => e.type === "end")
  const ended = Boolean(endEvent)
  const emptyText = connected ? t("logWaiting") : ended ? t("logNoRecords") : error ? t("logError") : t("logConnectingShort")
  // 圆点状态：解析 end 事件终态 outcome 后合成（终态覆盖连接态、不再回退）；纯逻辑见 run-dot-state.ts。
  const dotState: RunDotState = deriveRunDotState(parseEndState(endEvent?.data), ended, connected)
  // onDot 每次渲染都是新内联函数；用 ref 持有，effect 只依赖 dotState，避免「effect→setState→重渲染→新 onDot→effect」死循环。
  const onDotRef = useRef(onDot)
  onDotRef.current = onDot
  useEffect(() => {
    onDotRef.current(dotState)
  }, [dotState])

  // 自动滚动到底部（用户上滚则暂停）——经 OverlayScrollbars viewport。
  useEffect(() => {
    if (!autoScroll.current) return
    const vp = osRef.current?.osInstance()?.elements().viewport
    if (vp) vp.scrollTop = vp.scrollHeight
  }, [events])

  const handleScroll = () => {
    const vp = osRef.current?.osInstance()?.elements().viewport
    if (!vp) return
    autoScroll.current = vp.scrollHeight - vp.scrollTop - vp.clientHeight < 50
  }

  if (lines.length === 0) {
    return (
      <div className="flex h-full items-center justify-center bg-muted/20">
        {ended || error ? (
          <span className="text-muted-foreground text-xs">{emptyText}</span>
        ) : (
          <LoadingState text={emptyText} />
        )}
      </div>
    )
  }

  return (
    <OverlayScrollbarsComponent
      ref={osRef}
      element="div"
      className="h-full bg-muted/20"
      options={{
        scrollbars: { theme: "os-theme-dark", autoHide: "never" },
        overflow: { x: "hidden", y: "scroll" },
      }}
      events={{ scroll: handleScroll }}
    >
      <div className="px-3 py-2 font-mono text-xs leading-relaxed">
        <div className="space-y-px">
          {lines.map((line, i) => {
            const banner = line.startsWith("===")
            return (
              <div
                key={i}
                className={cn("whitespace-pre-wrap break-all", banner && "text-primary/70")}
              >
                {line}
              </div>
            )
          })}
        </div>
      </div>
    </OverlayScrollbarsComponent>
  )
}
