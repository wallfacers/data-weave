"use client"

import { useCallback, useEffect, useRef, useState } from "react"
import { usePathname, useSearchParams } from "next/navigation"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  AiChat01Icon,
  Cancel01Icon,
} from "@hugeicons/core-free-icons"

import { AgentChat, type AgentPageContext } from "@/components/agent-chat"
import { Button } from "@/components/ui/button"

/** 悬浮球初始位置：视口右侧距边 24px、垂直居中偏下 */
const INITIAL_POS = { x: -1, y: -1 } // -1 表示首次渲染时按视口计算
const DRAG_THRESHOLD = 4 // px：超过此距离才算拖拽，否则视为点击

/** 右舷面板宽度：默认 / 可拖拽范围 / 持久化键 */
const RAIL_DEFAULT_WIDTH = 440
const RAIL_MIN_WIDTH = 340
const RAIL_MAX_WIDTH = 680
const RAIL_WIDTH_KEY = "dw.agentRail.width"

/** 路由 → 模块名映射，用于标题行上下文展示 */
const MODULE_LABELS: Record<string, string> = {
  "/": "驾驶舱",
  "/tasks": "任务开发",
  "/ops": "调度运维",
  "/metrics": "指标体系",
  "/lineage": "数据血缘",
  "/fleet": "集群机器",
  "/diagnosis": "失败诊断",
  "/integration": "数据集成",
  "/catalog": "资产目录",
  "/quality": "数据质量",
  "/service": "数据服务",
}

/** 从 query 参数中提取上下文对象 */
function useContextObject(searchParams: URLSearchParams): string | null {
  return (
    searchParams.get("instanceId") ??
    searchParams.get("nodeId") ??
    searchParams.get("taskId") ??
    null
  )
}

export function AgentRail() {
  const [open, setOpen] = useState(true)
  const pathname = usePathname()
  const searchParams = useSearchParams()

  // ── 面板宽度（可拖拽分割线调整，localStorage 持久化）────────────
  // SSR 与首帧统一渲染默认宽，挂载后再从 localStorage 恢复，避免水合不一致。
  const [railWidth, setRailWidth] = useState(RAIL_DEFAULT_WIDTH)
  useEffect(() => {
    const saved = Number(localStorage.getItem(RAIL_WIDTH_KEY))
    if (saved >= RAIL_MIN_WIDTH && saved <= RAIL_MAX_WIDTH) setRailWidth(saved)
  }, [])

  // 左缘分割线按下：监听 window pointermove 改宽（面板在右，指针左移 → 变宽），松手持久化。
  const onResizeDown = useCallback(
    (e: React.PointerEvent) => {
      e.preventDefault()
      const startX = e.clientX
      const startW = railWidth
      let current = startW
      const onMove = (ev: PointerEvent) => {
        current = Math.min(
          RAIL_MAX_WIDTH,
          Math.max(RAIL_MIN_WIDTH, startW + (startX - ev.clientX)),
        )
        setRailWidth(current)
      }
      const onUp = () => {
        window.removeEventListener("pointermove", onMove)
        window.removeEventListener("pointerup", onUp)
        document.body.style.cursor = ""
        document.body.style.userSelect = ""
        localStorage.setItem(RAIL_WIDTH_KEY, String(current))
      }
      document.body.style.cursor = "col-resize"
      document.body.style.userSelect = "none"
      window.addEventListener("pointermove", onMove)
      window.addEventListener("pointerup", onUp)
    },
    [railWidth],
  )

  // ── 悬浮球拖拽 ──────────────────────────────────────────────
  // pos 用 fixed 定位坐标（右下角为初始位），首次渲染按 window 计算。
  const [pos, setPos] = useState<{ x: number; y: number }>(INITIAL_POS)
  const dragRef = useRef<{
    startX: number
    startY: number
    originX: number
    originY: number
    dragging: boolean
  } | null>(null)
  const btnRef = useRef<HTMLButtonElement>(null)
  /** 跨 pointerup→click 保留：拖拽过就阻止后续 click 打开面板 */
  const didDragRef = useRef(false)

  /** 首次渲染：把按钮放到视口右侧垂直居中偏下 */
  const resolvePos = useCallback((p: { x: number; y: number }) => {
    if (p.x >= 0) return p
    const vw = typeof window !== "undefined" ? window.innerWidth : 1200
    const vh = typeof window !== "undefined" ? window.innerHeight : 800
    return { x: vw - 24 - 40, y: vh / 2 + 60 }
  }, [])

  const onPointerDown = useCallback(
    (e: React.PointerEvent) => {
      e.currentTarget.setPointerCapture(e.pointerId)
      const cur = pos.x >= 0 ? pos : resolvePos(pos)
      didDragRef.current = false
      dragRef.current = {
        startX: e.clientX,
        startY: e.clientY,
        originX: cur.x,
        originY: cur.y,
        dragging: false,
      }
    },
    [pos, resolvePos],
  )

  const onPointerMove = useCallback((e: React.PointerEvent) => {
    const d = dragRef.current
    if (!d) return
    const dx = e.clientX - d.startX
    const dy = e.clientY - d.startY
    if (!d.dragging && Math.hypot(dx, dy) < DRAG_THRESHOLD) return
    d.dragging = true
    didDragRef.current = true
    const vw = typeof window !== "undefined" ? window.innerWidth : 1200
    const vh = typeof window !== "undefined" ? window.innerHeight : 800
    const size = 40 // 按钮尺寸
    setPos({
      x: Math.max(0, Math.min(d.originX + dx, vw - size)),
      y: Math.max(0, Math.min(d.originY + dy, vh - size)),
    })
  }, [])

  const onPointerUp = useCallback((e: React.PointerEvent) => {
    e.currentTarget.releasePointerCapture(e.pointerId)
    dragRef.current = null
  }, [])

  /** 仅在"没拖过"时响应 click，避免松手时误触打开面板 */
  const handleClick = useCallback(() => {
    if (didDragRef.current) return
    setOpen(true)
  }, [])

  const moduleName = MODULE_LABELS[pathname] ?? ""
  const contextObject = useContextObject(searchParams)

  // 逐消息页面上下文：随导航/选中对象变化，经 AgentChat → forwardedProps 送达后端（缺口①）。
  const pageContext: AgentPageContext = {
    module: pathname,
    pathname,
    taskId: searchParams.get("taskId") ?? undefined,
    instanceId: searchParams.get("instanceId") ?? undefined,
    nodeId: searchParams.get("nodeId") ?? undefined,
  }

  // 收起态：可拖拽悬浮球（fixed 定位）
  if (!open) {
    const resolved = resolvePos(pos)
    return (
      <Button
        ref={btnRef}
        size="icon"
        className="fixed z-50 size-10 cursor-grab rounded-full bg-primary text-primary-foreground shadow-md active:cursor-grabbing"
        style={{ left: resolved.x, top: resolved.y }}
        aria-label="展开 Agent 对话"
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onClick={handleClick}
      >
        <HugeiconsIcon icon={AiChat01Icon} />
      </Button>
    )
  }

  // 展开态：右舷悬浮面板。外层 w-[440px] 仅占位（保留独立列、不遮挡内容），
  // 四周留间距让内层卡片"浮"起来；内层用 bg-card + border + shadow-lg
  // 构成抬升层（背景区分 + 边框包裹），圆角走 DESIGN.md 的 --radius-lg。
  return (
    <div
      className="relative flex shrink-0 flex-col p-3 pl-1.5"
      style={{ width: railWidth }}
    >
      {/* 拖拽分割线：左缘透明命中区，hover 显一条细线；按下拖动改面板宽度 */}
      <div
        onPointerDown={onResizeDown}
        role="separator"
        aria-orientation="vertical"
        aria-label="拖拽调整 Agent 面板宽度"
        className="group/resize absolute inset-y-3 left-0 z-20 flex w-2 cursor-col-resize touch-none items-center justify-center"
      >
        <div className="h-12 w-0.5 rounded-full bg-border/0 transition-colors group-hover/resize:bg-border" />
      </div>
      <div className="flex min-h-0 flex-1 flex-col overflow-hidden rounded-[var(--radius-lg)] border bg-sidebar shadow-lg">
        {/* 标题行：14 高，无下边框（遵守无分割线规则） */}
        <div className="flex h-14 shrink-0 items-center gap-2 px-4">
          <span className="text-sm font-medium">Agent</span>
          {moduleName && (
            <span className="text-xs text-muted-foreground">
              当前：{moduleName}
              {contextObject && ` · ${contextObject}`}
            </span>
          )}
          <div className="flex-1" />
          <Button
            variant="ghost"
            size="icon"
            className="size-7"
            onClick={() => setOpen(false)}
            aria-label="收起 Agent 对话"
          >
            <HugeiconsIcon icon={Cancel01Icon} />
          </Button>
        </div>

        {/* Agent 对话区 */}
        <div className="flex min-h-0 flex-1 flex-col overflow-hidden">
          <AgentChat context={pageContext} />
        </div>
      </div>
    </div>
  )
}
