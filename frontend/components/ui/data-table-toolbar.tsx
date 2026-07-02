"use client"

/**
 * DataTableToolbar：按 FilterDef[] 渲染统一筛选词汇。
 *   search / segmented / multiSelect / dateRange / toggle
 * primary 常驻；advanced 收进「更多筛选」portal 弹层。提供语义化快捷预设 chips、
 * 激活计数与一键清空。本组件不持有数据，只受控管理筛选值（见 design.md D1）。
 */

import { useEffect, useMemo, useRef, useState, type ReactNode } from "react"
import { useLocale, useTranslations } from "next-intl"
import { createPortal } from "react-dom"
import { type Locale } from "date-fns"
import { zhCN, enUS } from "date-fns/locale"
import { HugeiconsIcon } from "@hugeicons/react"
import { Search01Icon, FilterIcon, Cancel01Icon } from "@hugeicons/core-free-icons"

import { cn } from "@/lib/utils"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Checkbox } from "@/components/ui/checkbox"
import { DatePicker } from "@/components/ui/date-picker"
import { DropdownSelect } from "@/components/ui/select"
import { DwScroll } from "@/components/ui/dw-scroll"
import {
  type FilterDef,
  type FilterValue,
  type FilterValues,
  type FilterPreset,
  type DateRangeValue,
  countActiveFilters,
  isFilterActive,
} from "@/lib/data-table"

interface ToolbarProps {
  filters: FilterDef[]
  values: FilterValues
  onChange: (key: string, value: FilterValue) => void
  onReset: () => void
  presets?: FilterPreset[]
  onApplyPreset?: (preset: FilterPreset) => void
  /** 右侧附加内容（如批量操作栏） */
  rightSlot?: ReactNode
}

export function DataTableToolbar({
  filters,
  values,
  onChange,
  onReset,
  presets,
  onApplyPreset,
  rightSlot,
}: ToolbarProps) {
  const t = useTranslations("dataTable")
  const locale = useLocale()
  const dateLocale = useMemo(() => (locale === "zh-CN" ? zhCN : enUS), [locale])
  const quickLabels = useMemo(() => ({ today: t("today"), yesterday: t("yesterday") }), [t])

  const primary = filters.filter((f) => (f.tier ?? "primary") === "primary")
  const advanced = filters.filter((f) => f.tier === "advanced")
  const activeCount = countActiveFilters(filters, values)

  const confirmLabel = t("confirm")

  const renderFilter = (def: FilterDef) => (
    <FilterControl
      key={def.key}
      def={def}
      value={values[def.key]}
      onChange={(v) => onChange(def.key, v)}
      dateLocale={dateLocale}
      quickLabels={quickLabels}
      searchPlaceholder={t("searchPlaceholder")}
      allLabel={t("allLabel")}
      confirmLabel={confirmLabel}
    />
  )

  return (
    <div className="flex shrink-0 flex-col gap-2">
      <div className="flex flex-wrap items-center gap-2">
        <HugeiconsIcon icon={FilterIcon} className="size-4 shrink-0 text-muted-foreground" />
        {primary.map(renderFilter)}
        {advanced.length > 0 && (
          <AdvancedPopover label={t("more")} activeCount={countActiveFilters(advanced, values)}>
            <div className="flex flex-col gap-3">{advanced.map(renderFilter)}</div>
          </AdvancedPopover>
        )}
        {activeCount > 0 && (
          <Button variant="ghost" size="sm" className="h-8 text-xs" onClick={onReset}>
            <HugeiconsIcon icon={Cancel01Icon} className="size-3.5" />
            {t("clear", { count: activeCount })}
          </Button>
        )}
        {rightSlot && <div className="ml-auto">{rightSlot}</div>}
      </div>
      {presets && presets.length > 0 && (
        <div className="flex flex-wrap items-center gap-1.5">
          <span className="text-xs text-muted-foreground">{t("quick")}</span>
          {presets.map((p) => (
            <button
              key={p.key}
              type="button"
              onClick={() => onApplyPreset?.(p)}
              className="rounded-full border border-input bg-background px-2.5 py-0.5 text-xs text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
            >
              {p.label}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}

// ──────────────────────────── 单条筛选控件 ────────────────────────────

function FilterControl({
  def,
  value,
  onChange,
  dateLocale,
  quickLabels,
  searchPlaceholder,
  allLabel,
  confirmLabel,
}: {
  def: FilterDef
  value: FilterValue | undefined
  onChange: (v: FilterValue) => void
  dateLocale: Locale
  quickLabels: { today: string; yesterday: string }
  searchPlaceholder: string
  allLabel: string
  confirmLabel: string
}) {
  switch (def.kind) {
    case "search":
      return <SearchControl value={(value as string) ?? ""} onChange={onChange} placeholder={def.placeholder ?? searchPlaceholder} width={def.width} />
    case "select":
      return (
        <DropdownSelect
          value={(value as string) ?? ""}
          onChange={onChange}
          options={[{ value: "", label: allLabel }, ...(def.options ?? [])]}
          placeholder={def.label}
          triggerClassName={cn("h-8", def.width)}
        />
      )
    case "segmented":
      return <SegmentedControl def={def} value={(value as string) ?? ""} onChange={onChange} allLabel={allLabel} />
    case "multiSelect":
      return <MultiSelectControl def={def} value={(value as string[]) ?? []} onChange={onChange} />
    case "date":
      return (
        <DatePicker
          value={(value as string) || undefined}
          onChange={onChange}
          placeholder={def.label}
          triggerClassName={cn("h-8", def.width ?? "w-40")}
          locale={dateLocale}
          quickLabels={quickLabels}
          showTime={def.showTime}
          confirmLabel={confirmLabel}
        />
      )
    case "dateRange":
      return (
        <DateRangeControl
          def={def}
          value={(value as DateRangeValue) ?? {}}
          onChange={onChange}
          dateLocale={dateLocale}
          quickLabels={quickLabels}
        />
      )
    case "toggle":
      return <ToggleControl def={def} value={value === true} onChange={onChange} />
    default:
      return null
  }
}

function SearchControl({
  value,
  onChange,
  placeholder,
  width,
}: {
  value: string
  onChange: (v: string) => void
  placeholder: string
  width?: string
}) {
  // 防抖：本地即时回显，250ms 后上抛
  const [local, setLocal] = useState(value)
  useEffect(() => setLocal(value), [value])
  useEffect(() => {
    const id = setTimeout(() => {
      if (local !== value) onChange(local)
    }, 250)
    return () => clearTimeout(id)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [local])

  return (
    <div className={cn("relative", width ?? "w-48")}>
      <HugeiconsIcon
        icon={Search01Icon}
        className="pointer-events-none absolute left-2 top-1/2 size-3.5 -translate-y-1/2 text-muted-foreground"
      />
      <Input
        className="h-8 pl-7 text-sm"
        placeholder={placeholder}
        value={local}
        onChange={(e) => setLocal(e.target.value)}
      />
    </div>
  )
}

function SegmentedControl({
  def,
  value,
  onChange,
  allLabel,
}: {
  def: FilterDef
  value: string
  onChange: (v: string) => void
  allLabel: string
}) {
  const opts = [{ value: "", label: allLabel }, ...(def.options ?? [])]
  return (
    <div className="inline-flex h-8 items-center rounded-md border border-input bg-background p-0.5">
      {opts.map((o) => (
        <button
          key={o.value}
          type="button"
          onClick={() => onChange(o.value)}
          className={cn(
            "h-7 rounded px-2 text-xs transition-colors",
            value === o.value
              ? "bg-muted font-medium text-foreground"
              : "text-muted-foreground hover:text-foreground",
          )}
        >
          {o.label}
        </button>
      ))}
    </div>
  )
}

function MultiSelectControl({
  def,
  value,
  onChange,
}: {
  def: FilterDef
  value: string[]
  onChange: (v: string[]) => void
}) {
  const opts = def.options ?? []
  const selectedLabels = opts.filter((o) => value.includes(o.value)).map((o) => o.label)
  const trigger = (
    <span className="flex min-w-0 items-center gap-1">
      <span className="truncate">
        {selectedLabels.length === 0
          ? def.label
          : selectedLabels.length <= 2
            ? selectedLabels.join("、")
            : `${def.label} · ${value.length}`}
      </span>
    </span>
  )
  return (
    <Popover triggerLabel={trigger} active={value.length > 0} width={def.width}>
      <div className="flex min-w-40 flex-col gap-0.5 p-1">
        {opts.map((o) => {
          const checked = value.includes(o.value)
          return (
            <button
              key={o.value}
              type="button"
              onMouseDown={(e) => e.stopPropagation()}
              onClick={() =>
                onChange(checked ? value.filter((v) => v !== o.value) : [...value, o.value])
              }
              className="flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm hover:bg-muted"
            >
              <Checkbox checked={checked} onChange={() => {}} aria-label={o.label} />
              <span className="truncate">{o.label}</span>
            </button>
          )
        })}
      </div>
    </Popover>
  )
}

function DateRangeControl({
  def,
  value,
  onChange,
  dateLocale,
  quickLabels,
}: {
  def: FilterDef
  value: DateRangeValue
  onChange: (v: DateRangeValue) => void
  dateLocale: Locale
  quickLabels: { today: string; yesterday: string }
}) {
  return (
    <div className="flex items-center gap-1">
      <DatePicker
        value={value.from}
        onChange={(d) => onChange({ ...value, from: d })}
        placeholder={def.label}
        triggerClassName="h-8 w-36"
        locale={dateLocale}
        quickLabels={quickLabels}
      />
      <span className="text-xs text-muted-foreground">~</span>
      <DatePicker
        value={value.to}
        onChange={(d) => onChange({ ...value, to: d })}
        placeholder={def.label}
        triggerClassName="h-8 w-36"
        locale={dateLocale}
        quickLabels={quickLabels}
      />
    </div>
  )
}

function ToggleControl({
  def,
  value,
  onChange,
}: {
  def: FilterDef
  value: boolean
  onChange: (v: boolean) => void
}) {
  return (
    <button
      type="button"
      onClick={() => onChange(!value)}
      className={cn(
        "flex h-8 items-center gap-1.5 rounded-md border px-2.5 text-xs transition-colors",
        value
          ? "border-primary bg-primary/10 font-medium text-foreground"
          : "border-input bg-background text-muted-foreground hover:text-foreground",
      )}
    >
      <Checkbox checked={value} onChange={() => {}} aria-label={def.label} />
      {def.label}
    </button>
  )
}

// ──────────────────────────── portal 弹层原语 ────────────────────────────

/** 复用 DropdownSelect 的定位思路：portal 到 body + fixed，点外部关闭 */
function Popover({
  triggerLabel,
  active,
  width,
  children,
}: {
  triggerLabel: ReactNode
  active?: boolean
  width?: string
  children: ReactNode
}) {
  const [open, setOpen] = useState(false)
  const btnRef = useRef<HTMLButtonElement>(null)
  const panelRef = useRef<HTMLDivElement>(null)
  const [anchor, setAnchor] = useState({ top: 0, left: 0, width: 0, placeBelow: true, maxH: 320 })

  const recompute = () => {
    if (!btnRef.current) return
    const rect = btnRef.current.getBoundingClientRect()
    const gap = 4
    const spaceBelow = window.innerHeight - rect.bottom - gap
    const spaceAbove = rect.top - gap
    const placeBelow = spaceBelow >= 120 || spaceBelow >= spaceAbove
    const maxH = placeBelow ? spaceBelow : spaceAbove
    setAnchor({
      top: placeBelow ? rect.bottom + gap : rect.top - gap,
      left: rect.left,
      width: Math.max(rect.width, 160),
      placeBelow,
      maxH: Math.min(360, Math.max(maxH, 80)),
    })
  }

  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => {
      if (btnRef.current?.contains(e.target as Node)) return
      if (panelRef.current?.contains(e.target as Node)) return
      setOpen(false)
    }
    const onScroll = (e: Event) => {
      if (panelRef.current?.contains(e.target as Node)) return
      recompute()
    }
    const onResize = () => setOpen(false)
    document.addEventListener("mousedown", onDown, true)
    window.addEventListener("scroll", onScroll, true)
    window.addEventListener("resize", onResize)
    return () => {
      document.removeEventListener("mousedown", onDown, true)
      window.removeEventListener("scroll", onScroll, true)
      window.removeEventListener("resize", onResize)
    }
  }, [open])

  return (
    <div className={cn("relative shrink-0", width)}>
      <button
        ref={btnRef}
        type="button"
        onMouseDown={(e) => e.stopPropagation()}
        onClick={(e) => {
          e.stopPropagation()
          if (!open) recompute()
          setOpen((v) => !v)
        }}
        aria-expanded={open}
        className={cn(
          "flex h-8 w-full min-w-0 items-center justify-between gap-1 rounded-md border px-2 text-sm transition-colors hover:bg-muted",
          active ? "border-primary text-foreground" : "border-input bg-background text-muted-foreground",
        )}
      >
        {triggerLabel}
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
      {open &&
        typeof document !== "undefined" &&
        createPortal(
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
              {children}
            </DwScroll>
          </div>,
          document.body,
        )}
    </div>
  )
}

function AdvancedPopover({
  label,
  activeCount,
  children,
}: {
  label: string
  activeCount: number
  children: ReactNode
}) {
  const triggerLabel = (
    <span className="flex items-center gap-1">
      {label}
      {activeCount > 0 && (
        <span className="flex size-4 items-center justify-center rounded-full bg-primary text-[10px] text-primary-foreground">
          {activeCount}
        </span>
      )}
    </span>
  )
  return (
    <Popover triggerLabel={triggerLabel} active={activeCount > 0}>
      <div className="min-w-56 p-3">{children}</div>
    </Popover>
  )
}
