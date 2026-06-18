"use client"

import { useEffect, useRef, useState, type ReactNode } from "react"
import { useTranslations } from "next-intl"
import { createPortal } from "react-dom"

import { cn } from "@/lib/utils"

export interface DropdownOption {
  value: string
  label: string
}

interface DropdownSelectProps {
  value: string
  onChange: (value: string) => void
  options: DropdownOption[]
  placeholder?: string
  className?: string
}

/**
 * 下拉选择：下拉面板 portal 到 body + fixed 定位（逃离任何 transform 祖先）。
 * - 触发按钮 mousedown stopPropagation，防止 toggle 与 document 关闭监听互相打架
 * - 下拉打开期间，document 级 mousedown 捕获：点外部即关闭
 * - scroll / resize 时自动关闭，避免位置漂移
 */
export function DropdownSelect({
  value,
  onChange,
  options,
  placeholder,
  className,
}: DropdownSelectProps) {
  const tc = useTranslations("common")
  const resolvedPlaceholder = placeholder ?? tc("selectPlaceholder")
  const [open, setOpen] = useState(false)
  const btnRef = useRef<HTMLButtonElement>(null)
  const panelRef = useRef<HTMLDivElement>(null)
  const [anchor, setAnchor] = useState({ top: 0, left: 0, width: 0 })

  const recomputeAnchor = () => {
    if (!btnRef.current) return
    const rect = btnRef.current.getBoundingClientRect()
    setAnchor({ top: rect.bottom + 4, left: rect.left, width: rect.width })
  }

  const toggle = (e: React.MouseEvent) => {
    e.preventDefault()
    e.stopPropagation()
    if (!open) recomputeAnchor()
    setOpen((v) => !v)
  }

  // 打开期间：点外部关闭；scroll / resize 关闭
  useEffect(() => {
    if (!open) return
    const onDocMouseDown = (e: MouseEvent) => {
      if (btnRef.current?.contains(e.target as Node)) return
      if (panelRef.current?.contains(e.target as Node)) return
      setOpen(false)
    }
    const onScrollOrResize = () => setOpen(false)
    document.addEventListener("mousedown", onDocMouseDown, true)
    window.addEventListener("scroll", onScrollOrResize, true)
    window.addEventListener("resize", onScrollOrResize)
    return () => {
      document.removeEventListener("mousedown", onDocMouseDown, true)
      window.removeEventListener("scroll", onScrollOrResize, true)
      window.removeEventListener("resize", onScrollOrResize)
    }
  }, [open])

  const selected = options.find((o) => o.value === value)
  const displayLabel = selected ? selected.label : resolvedPlaceholder

  let dropdown: ReactNode = null
  if (open && typeof document !== "undefined") {
    dropdown = createPortal(
      <div
        ref={panelRef}
        className="fixed z-50 flex flex-col gap-0.5 rounded-lg border bg-popover p-1 shadow-md"
        style={{
          top: anchor.top,
          left: anchor.left,
          minWidth: anchor.width,
        }}
      >
        {options.map((opt) => (
          <button
            key={opt.value}
            type="button"
            onMouseDown={(e) => e.stopPropagation()}
            onClick={() => {
              onChange(opt.value)
              setOpen(false)
            }}
            className={cn(
              "flex items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm hover:bg-muted",
              opt.value === value
                ? "font-medium text-foreground"
                : "text-popover-foreground",
            )}
          >
            {opt.value === value && (
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
            )}
            {opt.value !== value && <span className="size-3.5" />}
            {opt.label}
          </button>
        ))}
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
          "flex h-8 items-center gap-1 rounded-md border border-input bg-background px-2 text-sm text-foreground transition-colors hover:bg-muted",
          !selected && "text-muted-foreground",
        )}
        aria-expanded={open}
      >
        <span className="truncate">{displayLabel}</span>
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
