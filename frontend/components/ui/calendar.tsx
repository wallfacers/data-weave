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
        day: cn(
          // v9: <td role="gridcell"> 单元格本体，承载 aria-selected 与布局尺寸
          "group/day size-8 p-0 relative text-center text-sm"
        ),
        day_button: cn(
          // v9: 单元格内的 <button>，铺满整个格子 → 灰色悬停区全可点击
          "size-full flex items-center justify-center rounded-md text-sm font-normal cursor-pointer transition-colors",
          "hover:bg-muted",
          "group-aria-selected/day:bg-primary group-aria-selected/day:text-primary-foreground group-aria-selected/day:hover:bg-primary",
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
