"use client"

import {
  createContext,
  useContext,
  useMemo,
  useRef,
  type ReactNode,
  type KeyboardEvent,
} from "react"
import { HugeiconsIcon, type IconSvgElement } from "@hugeicons/react"

import { cn } from "@/lib/utils"

// ── Root context ────────────────────────────────────────────────

interface TabsRootContext {
  value: string
  onValueChange: (value: string) => void
}

const TabsRootContext = createContext<TabsRootContext | null>(null)

function useTabsRoot() {
  const ctx = useContext(TabsRootContext)
  if (!ctx) throw new Error("Tabs components must be used within <Tabs>")
  return ctx
}

// ── Size tokens ─────────────────────────────────────────────────

const UNDERLINE_SIZE: Record<string, string> = {
  md: "h-10 text-sm px-4",
  sm: "h-8 text-xs px-3",
}

const UNDERLINE_ICON: Record<string, string> = {
  md: "size-3.5",
  sm: "size-3",
}

// ── List context (size only) ────────────────────────────────────

const TabsListContext = createContext<"md" | "sm">("md")

function useTabsListSize() {
  return useContext(TabsListContext)
}

// ── Helpers: extract trigger values from children ───────────────

function extractValues(children: ReactNode): string[] {
  const result: string[] = []
  const arr = Array.isArray(children) ? children : [children]
  for (const child of arr) {
    if (child && typeof child === "object" && "props" in child) {
      const p = child.props as Record<string, unknown>
      if (typeof p.value === "string") result.push(p.value)
    }
  }
  return result
}

// ═══════════════════════════════════════════════════════════════
// Tabs (root)
// ═══════════════════════════════════════════════════════════════

export interface TabsProps {
  value: string
  onValueChange: (value: string) => void
  children: ReactNode
  className?: string
}

export function Tabs({ value, onValueChange, children, className }: TabsProps) {
  return (
    <TabsRootContext.Provider value={useMemo(() => ({ value, onValueChange }), [value, onValueChange])}>
      <div className={className}>{children}</div>
    </TabsRootContext.Provider>
  )
}

// ═══════════════════════════════════════════════════════════════
// TabsList
// ═══════════════════════════════════════════════════════════════

export interface TabsListProps {
  children: ReactNode
  size?: "md" | "sm"
  className?: string
  trailing?: ReactNode
}

export function TabsList({
  children,
  size = "md",
  className,
  trailing,
}: TabsListProps) {
  const { value: activeValue, onValueChange } = useTabsRoot()
  const listRef = useRef<HTMLDivElement>(null)
  const values = useMemo(() => extractValues(children), [children])

  const handleKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    const dir = e.key === "ArrowRight" ? 1 : e.key === "ArrowLeft" ? -1 : null
    if (!dir) return
    e.preventDefault()
    const idx = values.indexOf(activeValue)
    const next = (idx + dir + values.length) % values.length
    onValueChange(values[next])
    requestAnimationFrame(() => {
      const el = listRef.current?.querySelector(`[data-tab-value="${values[next]}"]`) as HTMLElement | null
      el?.focus()
    })
  }

  return (
    <TabsListContext.Provider value={size}>
      <div
        ref={listRef}
        role="tablist"
        aria-orientation="horizontal"
        onKeyDown={handleKeyDown}
        className={cn("flex items-center border-b border-border", className)}
      >
        {children}
        {trailing && <div className="flex shrink-0 items-center ml-auto">{trailing}</div>}
      </div>
    </TabsListContext.Provider>
  )
}

// ═══════════════════════════════════════════════════════════════
// TabsTrigger
// ═══════════════════════════════════════════════════════════════

export interface TabsTriggerProps {
  value: string
  children: ReactNode
  disabled?: boolean
  className?: string
  icon?: IconSvgElement
  indicator?: ReactNode
  suffix?: ReactNode
  monospace?: boolean
}

export function TabsTrigger({
  value,
  children,
  disabled,
  className,
  icon,
  indicator,
  suffix,
  monospace,
}: TabsTriggerProps) {
  const { value: activeValue, onValueChange } = useTabsRoot()
  const size = useTabsListSize()

  const active = activeValue === value
  const baseSize = UNDERLINE_SIZE[size]
  const iconSize = UNDERLINE_ICON[size]

  return (
    <button
      role="tab"
      aria-selected={active}
      tabIndex={disabled ? -1 : 0}
      data-tab-value={value}
      disabled={disabled}
      onClick={() => { if (!disabled) onValueChange(value) }}
      onKeyDown={(e) => {
        if ((e.key === "Enter" || e.key === " ") && !disabled) {
          e.preventDefault()
          onValueChange(value)
        }
      }}
      className={cn(
        "relative inline-flex items-center justify-center whitespace-nowrap transition-colors",
        "border-b-2 -mb-px",
        "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2",
        active
          ? "border-primary text-primary font-medium"
          : "border-transparent text-muted-foreground hover:text-foreground",
        disabled && "cursor-not-allowed opacity-50",
        baseSize,
        className,
      )}
    >
      {indicator && <span className="flex shrink-0 items-center mr-1.5">{indicator}</span>}
      {icon && <HugeiconsIcon icon={icon} className={cn("shrink-0 mr-1.5", iconSize)} />}
      <span className={cn(monospace && "font-mono")}>{children}</span>
      {suffix && <span className="flex shrink-0 items-center ml-1.5">{suffix}</span>}
    </button>
  )
}

// ═══════════════════════════════════════════════════════════════
// TabsContent
// ═══════════════════════════════════════════════════════════════

export interface TabsContentProps {
  value: string
  children: ReactNode
  className?: string
}

export function TabsContent({ value, children, className }: TabsContentProps) {
  const { value: activeValue } = useTabsRoot()
  if (value !== activeValue) return null
  return (
    <div role="tabpanel" className={className}>
      {children}
    </div>
  )
}
