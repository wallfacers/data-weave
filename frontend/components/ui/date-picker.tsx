"use client"

import * as React from "react"
import { format, parse, subDays, setHours, setMinutes, setSeconds, type Locale } from "date-fns"
import { HugeiconsIcon } from "@hugeicons/react"
import { Calendar01Icon } from "@hugeicons/core-free-icons"

import { cn } from "@/lib/utils"
import { Popover, PopoverTrigger, PopoverContent } from "@/components/ui/popover"
import { Calendar } from "@/components/ui/calendar"

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

/** Parse a date string (date-only or datetime) into a Date, or return undefined. */
function parseDatetime(value: string | undefined): Date | undefined {
  if (!value) return undefined
  try {
    // Try datetime format first
    return parse(value, DATETIME_FMT, new Date())
  } catch {
    try {
      // Fall back to date-only format
      return parse(value, DATE_FMT, new Date())
    } catch {
      return undefined
    }
  }
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
  confirmLabel = "确认",
}: DatePickerProps) {
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
      setPendingDate(selected)
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

  function handleTimeChange(field: "h" | "m" | "s", raw: string) {
    const digits = raw.replace(/\D/g, "").slice(0, 2)
    const num = parseInt(digits, 10)
    let clamped: string
    if (field === "h") {
      clamped = isNaN(num) ? "00" : String(Math.min(num, 23)).padStart(2, "0")
    } else {
      clamped = isNaN(num) ? "00" : String(Math.min(num, 59)).padStart(2, "0")
    }
    setTimeDraft((prev) => ({ ...prev, [field]: clamped }))
  }

  const today = new Date()
  const yesterday = subDays(today, 1)
  const todayLabel = quickLabels?.today ?? "Today"
  const yesterdayLabel = quickLabels?.yesterday ?? "Yesterday"

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
            <input
              type="text"
              inputMode="numeric"
              value={timeDraft.h}
              onChange={(e) => handleTimeChange("h", e.target.value)}
              className="h-8 w-10 rounded border border-input bg-background text-center text-sm tabular-nums"
              placeholder="HH"
              maxLength={2}
            />
            <span className="text-muted-foreground">:</span>
            <input
              type="text"
              inputMode="numeric"
              value={timeDraft.m}
              onChange={(e) => handleTimeChange("m", e.target.value)}
              className="h-8 w-10 rounded border border-input bg-background text-center text-sm tabular-nums"
              placeholder="MM"
              maxLength={2}
            />
            <span className="text-muted-foreground">:</span>
            <input
              type="text"
              inputMode="numeric"
              value={timeDraft.s}
              onChange={(e) => handleTimeChange("s", e.target.value)}
              className="h-8 w-10 rounded border border-input bg-background text-center text-sm tabular-nums"
              placeholder="SS"
              maxLength={2}
            />
            <button
              type="button"
              onClick={handleConfirm}
              className="ml-2 h-8 rounded-md bg-primary px-3 text-xs font-medium text-primary-foreground transition-colors hover:bg-primary/90"
            >
              {confirmLabel}
            </button>
          </div>
        )}
      </PopoverContent>
    </Popover>
  )
}

export { DatePicker }
