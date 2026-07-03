"use client"

import * as React from "react"
import { useTranslations } from "next-intl"
import { format, parse, isValid, subDays, setHours, setMinutes, setSeconds, type Locale } from "date-fns"
import { HugeiconsIcon } from "@hugeicons/react"
import { Calendar01Icon } from "@hugeicons/core-free-icons"

import { cn } from "@/lib/utils"
import { Popover, PopoverTrigger, PopoverContent } from "@/components/ui/popover"
import { Calendar } from "@/components/ui/calendar"
import { DwScroll } from "@/components/ui/dw-scroll"

interface DatePickerProps {
  value?: string
  onChange: (date: string) => void
  placeholder?: string
  className?: string
  triggerClassName?: string
  /** date-fns locale for calendar i18n (month/weekday names, week start) */
  locale?: Locale
  /** Show Today / Yesterday quick-select buttons below the calendar. Default true. */
  showQuickActions?: boolean
  /** Override labels for quick-action buttons */
  quickLabels?: { today?: string; yesterday?: string }
  /** When true, always show ▼ even when a date is selected (required fields). Default false. */
  disableClear?: boolean
  /** When true, show time (HH:mm:ss) inputs below the calendar. Format becomes yyyy-MM-dd'T'HH:mm:ss. */
  showTime?: boolean
  /** Label for the confirm button when showTime is true. Default "确认". */
  confirmLabel?: string
}

const DATE_FMT = "yyyy-MM-dd"
const DATETIME_FMT = "yyyy-MM-dd'T'HH:mm:ss"
const DISPLAY_DATETIME_FMT = "yyyy-MM-dd HH:mm:ss"

const HOURS = Array.from({ length: 24 }, (_, i) => String(i).padStart(2, "0"))
const MINUTES_SECONDS = Array.from({ length: 60 }, (_, i) => String(i).padStart(2, "0"))

/** Parse a date string (date-only or datetime) into a Date, or return undefined. */
function parseDatetime(value: string | undefined): Date | undefined {
  if (!value) return undefined
  // Try datetime format first
  const dt = parse(value, DATETIME_FMT, new Date())
  if (isValid(dt)) return dt
  // Fall back to date-only format
  const d = parse(value, DATE_FMT, new Date())
  if (isValid(d)) return d
  return undefined
}

/** 时/分/秒下拉：自定义面板 + DwScroll，避免原生 select 弹层过高、滚动条不统一。 */
function TimeUnitPicker({
  value,
  options,
  onChange,
}: {
  value: string
  options: string[]
  onChange: (v: string) => void
}) {
  const [open, setOpen] = React.useState(false)
  const wrapRef = React.useRef<HTMLDivElement>(null)
  const listRef = React.useRef<HTMLDivElement>(null)

  React.useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => {
      if (!wrapRef.current?.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener("mousedown", onDown, true)
    return () => document.removeEventListener("mousedown", onDown, true)
  }, [open])

  React.useEffect(() => {
    if (!open) return
    listRef.current?.querySelector<HTMLElement>(`[data-value="${value}"]`)?.scrollIntoView({ block: "center" })
  }, [open, value])

  return (
    <div ref={wrapRef} className="relative">
      <button
        type="button"
        onClick={(e) => {
          e.stopPropagation()
          setOpen((v) => !v)
        }}
        className="h-8 w-10 cursor-pointer rounded border border-input bg-background text-center text-sm tabular-nums hover:bg-muted"
      >
        {value}
      </button>
      {open && (
        <div className="absolute left-0 top-[calc(100%+4px)] z-50 w-12 rounded-lg border bg-popover shadow-md">
          <DwScroll className="max-h-40" direction="vertical">
            <div ref={listRef} className="flex flex-col gap-0.5 p-1">
              {options.map((o) => (
                <button
                  key={o}
                  type="button"
                  data-value={o}
                  onClick={() => {
                    onChange(o)
                    setOpen(false)
                  }}
                  className={cn(
                    "rounded px-2 py-1 text-center text-sm tabular-nums hover:bg-muted",
                    o === value ? "bg-muted font-medium text-foreground" : "text-popover-foreground",
                  )}
                >
                  {o}
                </button>
              ))}
            </div>
          </DwScroll>
        </div>
      )}
    </div>
  )
}

function DatePicker({
  value,
  onChange,
  placeholder = "Pick a date",
  className,
  triggerClassName,
  locale,
  showQuickActions = true,
  quickLabels,
  disableClear = false,
  showTime = false,
  confirmLabel,
}: DatePickerProps) {
  const t = useTranslations("datePicker")
  const [open, setOpen] = React.useState(false)
  const fmt = showTime ? DATETIME_FMT : DATE_FMT
  const displayFmt = showTime ? DISPLAY_DATETIME_FMT : DATE_FMT

  const selected = React.useMemo(() => parseDatetime(value), [value])

  // Time state (only used when showTime)
  const [timeDraft, setTimeDraft] = React.useState({ h: "00", m: "00", s: "00" })
  const [pendingDate, setPendingDate] = React.useState<Date | undefined>(undefined)

  // Sync time draft from selected value when opened
  React.useEffect(() => {
    if (open && showTime) {
      const d = selected ?? new Date()
      setPendingDate(selected ?? new Date())
      setTimeDraft({
        h: String(d.getHours()).padStart(2, "0"),
        m: String(d.getMinutes()).padStart(2, "0"),
        s: String(d.getSeconds()).padStart(2, "0"),
      })
    }
  }, [open, showTime]) // eslint-disable-line react-hooks/exhaustive-deps

  function handleSelect(date: Date | undefined) {
    if (!showTime) {
      if (date) {
        onChange(format(date, DATE_FMT))
      } else {
        onChange("")
      }
      setOpen(false)
      return
    }
    // With time: set pending date but don't close yet
    if (date) {
      setPendingDate(date)
    }
  }

  function handleConfirm() {
    if (!pendingDate) return
    const h = parseInt(timeDraft.h, 10) || 0
    const m = parseInt(timeDraft.m, 10) || 0
    const s = parseInt(timeDraft.s, 10) || 0
    const dt = setSeconds(setMinutes(setHours(pendingDate, h), m), s)
    onChange(format(dt, DATETIME_FMT))
    setOpen(false)
  }

  function handleQuick(date: Date) {
    if (!showTime) {
      onChange(format(date, DATE_FMT))
      setOpen(false)
      return
    }
    // With time: set date as pending, keep current time draft
    setPendingDate(date)
  }

  function handleTimeChange(field: "h" | "m" | "s", value: string) {
    setTimeDraft((prev) => ({ ...prev, [field]: value }))
  }

  const today = new Date()
  const yesterday = subDays(today, 1)
  const todayLabel = quickLabels?.today ?? t("today")
  const yesterdayLabel = quickLabels?.yesterday ?? t("yesterday")

  const displayValue = React.useMemo(() => {
    if (!value) return null
    const d = parseDatetime(value)
    return d ? format(d, displayFmt) : value
  }, [value, displayFmt])

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger
        render={
          <button
            type="button"
            className={cn(
              "flex h-8 w-full min-w-0 items-center justify-between gap-1 rounded-md border border-input bg-background px-2 text-sm transition-colors hover:bg-muted",
              !value && "text-muted-foreground",
              triggerClassName
            )}
          >
            <HugeiconsIcon icon={Calendar01Icon} className="size-3.5 shrink-0 text-muted-foreground" />
            <span className="min-w-0 flex-1 truncate text-left">
              {displayValue ?? placeholder}
            </span>
            {value && !disableClear ? (
              <span
                role="button"
                aria-label="Clear date"
                className="shrink-0 rounded p-px text-muted-foreground transition-colors hover:text-foreground cursor-pointer"
                onMouseDown={(e) => {
                  e.preventDefault()
                  e.stopPropagation()
                  onChange("")
                  setOpen(false)
                  setPendingDate(undefined)
                }}
              >
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
                  className="size-3.5"
                >
                  <path d="M18 6 6 18" />
                  <path d="m6 6 12 12" />
                </svg>
              </span>
            ) : (
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
            )}
          </button>
        }
      />
      <PopoverContent className={cn("w-auto p-0", className)} align="start">
        <Calendar
          mode="single"
          selected={showTime ? pendingDate : selected}
          onSelect={handleSelect}
          initialFocus
          locale={locale}
        />
        {showQuickActions && (
          <div className="flex items-center gap-1 border-t px-3 py-2">
            <button
              type="button"
              onClick={() => handleQuick(today)}
              className="flex-1 rounded-md px-2 py-1 text-xs font-medium text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
            >
              {todayLabel}
            </button>
            <button
              type="button"
              onClick={() => handleQuick(yesterday)}
              className="flex-1 rounded-md px-2 py-1 text-xs font-medium text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
            >
              {yesterdayLabel}
            </button>
          </div>
        )}
        {showTime && (
          <div className="flex items-center gap-1 border-t px-3 py-2">
            <TimeUnitPicker value={timeDraft.h} options={HOURS} onChange={(v) => handleTimeChange("h", v)} />
            <span className="text-muted-foreground">:</span>
            <TimeUnitPicker value={timeDraft.m} options={MINUTES_SECONDS} onChange={(v) => handleTimeChange("m", v)} />
            <span className="text-muted-foreground">:</span>
            <TimeUnitPicker value={timeDraft.s} options={MINUTES_SECONDS} onChange={(v) => handleTimeChange("s", v)} />
            <button
              type="button"
              onClick={handleConfirm}
              className="ml-2 h-8 rounded-md bg-primary px-3 text-xs font-medium text-primary-foreground transition-colors hover:bg-primary/90"
            >
              {confirmLabel ?? t("confirm")}
            </button>
          </div>
        )}
      </PopoverContent>
    </Popover>
  )
}

export { DatePicker }
