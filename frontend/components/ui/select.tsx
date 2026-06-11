"use client"

import { useRef, useState, type ReactNode } from "react"

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
 * 手写 fixed 定位下拉，参照 workspace/tab-bar Launcher 模式。
 * getBoundingClientRect 同步抓坐标 + fixed 逃离 overflow 裁切 + 透明 backdrop 点外关闭。
 */
export function DropdownSelect({
  value,
  onChange,
  options,
  placeholder = "请选择",
  className,
}: DropdownSelectProps) {
  const [open, setOpen] = useState(false)
  const btnRef = useRef<HTMLButtonElement>(null)
  const [anchor, setAnchor] = useState({ top: 0, left: 0 })

  const toggle = () => {
    if (!open && btnRef.current) {
      const rect = btnRef.current.getBoundingClientRect()
      setAnchor({ top: rect.bottom + 4, left: rect.left })
    }
    setOpen((v) => !v)
  }

  const selected = options.find((o) => o.value === value)
  const displayLabel = selected ? selected.label : placeholder

  return (
    <div className={cn("relative shrink-0", className)}>
      <button
        ref={btnRef}
        type="button"
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
      {open && (
        <>
          <div
            className="fixed inset-0 z-40"
            onClick={() => setOpen(false)}
            aria-hidden
          />
          <div
            className="fixed z-50 flex min-w-[var(--trigger-width)] flex-col gap-0.5 rounded-lg border bg-popover p-1 shadow-md"
            style={{ top: anchor.top, left: anchor.left, "--trigger-width": `${btnRef.current?.offsetWidth ?? 100}px` } as React.CSSProperties}
          >
            {options.map((opt) => (
              <button
                key={opt.value}
                type="button"
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
          </div>
        </>
      )}
    </div>
  )
}
