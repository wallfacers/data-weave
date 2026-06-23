"use client"

import { useEffect, useRef, useState, type ReactNode } from "react"
import { useTranslations } from "next-intl"
import { createPortal } from "react-dom"

import { cn } from "@/lib/utils"
import { DwScroll } from "@/components/ui/dw-scroll"

export interface DropdownOption {
  value: string
  label: string
  /** Optional group key — render with section headers when `groups` prop is provided */
  group?: string
}

export interface DropdownGroup {
  value: string
  label: string
}

interface DropdownSelectProps {
  value: string
  onChange: (value: string) => void
  options: DropdownOption[]
  placeholder?: string
  className?: string
  /** When provided, options are rendered under group headers in this order */
  groups?: DropdownGroup[]
  /** Extra classes for the trigger button (e.g. "h-9" to match form inputs) */
  triggerClassName?: string
}

/**
 * 下拉选择：下拉面板 portal 到 body + fixed 定位（逃离任何 transform 祖先）。
 * - 触发按钮 mousedown stopPropagation，防止 toggle 与 document 关闭监听互相打架
 * - 下拉打开期间，document 级 mousedown 捕获：点外部即关闭
 * - 面板内滚动使用 DwScroll（OverlayScrollbars），无浏览器原生箭头
 * - 空间不足时向上弹出；resize 时关闭，scroll 时跟随触发按钮重新定位
 */
export function DropdownSelect({
  value,
  onChange,
  options,
  placeholder,
  className,
  groups,
  triggerClassName,
}: DropdownSelectProps) {
  const tc = useTranslations("common")
  const resolvedPlaceholder = placeholder ?? tc("selectPlaceholder")
  const [open, setOpen] = useState(false)
  const btnRef = useRef<HTMLButtonElement>(null)
  const panelRef = useRef<HTMLDivElement>(null)
  const [anchor, setAnchor] = useState({
    top: 0,
    left: 0,
    width: 0,
    placeBelow: true,
    maxH: 320,
  })

  const recomputeAnchor = () => {
    if (!btnRef.current) return
    const rect = btnRef.current.getBoundingClientRect()
    const gap = 4
    const spaceBelow = window.innerHeight - rect.bottom - gap
    const spaceAbove = rect.top - gap
    // 默认向下弹出；下方不足 100px 且上方空间更大时向上弹出
    const placeBelow = spaceBelow >= 100 || spaceBelow >= spaceAbove
    const maxH = placeBelow ? spaceBelow : spaceAbove
    setAnchor({
      top: placeBelow ? rect.bottom + gap : rect.top - gap,
      left: rect.left,
      width: Math.max(rect.width, 160),
      placeBelow,
      maxH: Math.min(320, Math.max(maxH, 80)),
    })
  }

  const toggle = (e: React.MouseEvent) => {
    e.preventDefault()
    e.stopPropagation()
    if (!open) recomputeAnchor()
    setOpen((v) => !v)
  }

  // 打开期间：点外部关闭；scroll 时跟随按钮重新定位（面板内滚动不关闭）
  useEffect(() => {
    if (!open) return
    const onDocMouseDown = (e: MouseEvent) => {
      if (btnRef.current?.contains(e.target as Node)) return
      if (panelRef.current?.contains(e.target as Node)) return
      setOpen(false)
    }
    const onScroll = (e: Event) => {
      // 面板自身滚动不处理（由 DwScroll 接管）
      if (panelRef.current?.contains(e.target as Node)) return
      // 页面/Dialog 滚动：跟随按钮重新定位
      recomputeAnchor()
    }
    const onResize = () => setOpen(false)
    document.addEventListener("mousedown", onDocMouseDown, true)
    window.addEventListener("scroll", onScroll, true)
    window.addEventListener("resize", onResize)
    return () => {
      document.removeEventListener("mousedown", onDocMouseDown, true)
      window.removeEventListener("scroll", onScroll, true)
      window.removeEventListener("resize", onResize)
    }
  }, [open])

  const selected = options.find((o) => o.value === value)
  const displayLabel = selected ? selected.label : resolvedPlaceholder

  let dropdown: ReactNode = null
  if (open && typeof document !== "undefined") {
    const renderOption = (opt: DropdownOption) => (
      <button
        key={opt.value}
        type="button"
        onMouseDown={(e) => e.stopPropagation()}
        onClick={() => {
          onChange(opt.value)
          setOpen(false)
        }}
        className={cn(
          "flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm hover:bg-muted",
          opt.value === value
            ? "font-medium text-foreground"
            : "text-popover-foreground",
        )}
      >
        {opt.value === value ? (
          <svg
            xmlns="http://www.w3.org/2000/svg"
            width="16"
            height="16"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
            className="size-3.5 shrink-0"
          >
            <path d="M20 6 9 17l-5-5" />
          </svg>
        ) : (
          <span className="size-3.5 shrink-0" />
        )}
        <span className="truncate">{opt.label}</span>
      </button>
    )

    let panelContent: ReactNode
    if (groups && groups.length > 0) {
      panelContent = groups.map((g) => {
        const items = options.filter((o) => o.group === g.value)
        if (items.length === 0) return null
        return (
          <div key={g.value}>
            <div className="px-2 py-1 text-xs font-medium text-muted-foreground">
              {g.label}
            </div>
            {items.map(renderOption)}
          </div>
        )
      })
    } else {
      panelContent = options.map(renderOption)
    }

    dropdown = createPortal(
      <div
        ref={panelRef}
        className="fixed z-50 flex flex-col rounded-lg border bg-popover shadow-md"
        style={{
          top: anchor.placeBelow ? anchor.top : undefined,
          bottom: anchor.placeBelow ? undefined : window.innerHeight - anchor.top,
          left: anchor.left,
          minWidth: anchor.width,
          maxHeight: anchor.maxH,
        }}
      >
        <DwScroll className="flex-1 min-h-0" direction="vertical">
          <div className="flex flex-col gap-0.5 p-1">{panelContent}</div>
        </DwScroll>
      </div>,
      document.body,
    )
  }

  return (
    <div className={cn("relative shrink-0", className)}>
      <button
        ref={btnRef}
        type="button"
        onMouseDown={(e) => e.stopPropagation()}
        onClick={toggle}
        className={cn(
          "flex h-8 w-full min-w-0 items-center justify-between gap-1 rounded-md border border-input bg-background px-2 text-sm text-foreground transition-colors hover:bg-muted",
          !selected && "text-muted-foreground",
          triggerClassName,
        )}
        aria-expanded={open}
      >
        <span className="min-w-0 flex-1 truncate text-left">{displayLabel}</span>
        <svg
          xmlns="http://www.w3.org/2000/svg"
          width="16"
          height="16"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
          className="size-3.5 shrink-0 text-muted-foreground"
        >
          <path d="m6 9 6 6 6-6" />
        </svg>
      </button>
      {dropdown}
    </div>
  )
}
