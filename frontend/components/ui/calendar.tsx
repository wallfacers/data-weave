"use client"

import * as React from "react"
import { DayPicker } from "react-day-picker"
import { HugeiconsIcon } from "@hugeicons/react"
import { ChevronLeftIcon, ChevronRightIcon } from "@hugeicons/core-free-icons"

import { cn } from "@/lib/utils"

export type CalendarProps = React.ComponentProps<typeof DayPicker>

function Calendar({
  className,
  classNames,
  showOutsideDays = true,
  ...props
}: CalendarProps) {
  return (
    <DayPicker
      showOutsideDays={showOutsideDays}
      className={cn("p-3", className)}
      classNames={{
        months: "flex flex-col gap-4",
        month: "flex flex-col gap-4",
        caption: "flex items-center justify-between px-1 h-9",
        caption_label: "text-sm font-medium",
        nav: "flex items-center gap-1",
        nav_button: cn(
          "inline-flex size-8 items-center justify-center rounded-md border-0 bg-transparent p-0 text-foreground transition-colors hover:bg-muted"
        ),
        table: "w-full border-collapse space-y-1",
        head_row: "flex w-full",
        head_cell:
          "size-8 text-[0.8rem] font-normal text-muted-foreground flex items-center justify-center",
        row: "flex w-full mt-1",
        cell: cn(
          "size-8 text-center text-sm p-0 relative flex items-center justify-center",
          "[&:has([aria-selected])]:bg-accent [&:has([aria-selected].day-outside)]:bg-accent/50",
          "[&:has([aria-selected].day-range-end)]:rounded-r-md"
        ),
        day: cn(
          "size-8 rounded-md text-sm font-normal transition-colors",
          "leading-8 text-center align-middle",
          "hover:bg-muted",
          "aria-selected:bg-primary aria-selected:text-primary-foreground",
          "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
        ),
        day_today: "bg-muted font-semibold",
        day_outside:
          "text-muted-foreground/50 aria-selected:text-muted-foreground/50",
        day_disabled: "text-muted-foreground/30",
        day_range_middle:
          "rounded-none aria-selected:bg-accent aria-selected:text-accent-foreground",
        day_hidden: "invisible",
        ...classNames,
      }}
      components={{
        Chevron: ({ orientation }) => {
          const Icon = orientation === "left" ? ChevronLeftIcon : ChevronRightIcon
          return <HugeiconsIcon icon={Icon} className="size-4" />
        },
      }}
      {...props}
    />
  )
}

export { Calendar }
