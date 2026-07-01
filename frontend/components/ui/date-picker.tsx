"use client"

import * as React from "react"
import { format, parse, subDays, type Locale } from "date-fns"
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
}

const DATE_FMT = "yyyy-MM-dd"

function DatePicker({
  value,
  onChange,
  placeholder = "Pick a date",
  className,
  triggerClassName,
  locale,
  showQuickActions = true,
  quickLabels,
}: DatePickerProps) {
  const [open, setOpen] = React.useState(false)

  const selected = React.useMemo(() => {
    if (!value) return undefined
    try {
      return parse(value, DATE_FMT, new Date())
    } catch {
      return undefined
    }
  }, [value])

  function handleSelect(date: Date | undefined) {
    if (date) {
      onChange(format(date, DATE_FMT))
    } else {
      // 日历中点击已选日期 → 取消选择
      onChange("")
    }
    setOpen(false)
  }

  function handleQuick(date: Date) {
    onChange(format(date, DATE_FMT))
    setOpen(false)
  }

  const today = new Date()
  const yesterday = subDays(today, 1)
  const todayLabel = quickLabels?.today ?? "Today"
  const yesterdayLabel = quickLabels?.yesterday ?? "Yesterday"

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
              {value ?? placeholder}
            </span>
            {value ? (
              <span
                role="button"
                aria-label="Clear date"
                className="shrink-0 rounded p-px text-muted-foreground transition-colors hover:text-foreground cursor-pointer"
                onMouseDown={(e) => {
                  e.preventDefault()
                  e.stopPropagation()
                  onChange("")
                  setOpen(false)
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
          selected={selected}
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
      </PopoverContent>
    </Popover>
  )
}

export { DatePicker }
