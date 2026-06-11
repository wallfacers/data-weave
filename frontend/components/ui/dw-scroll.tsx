"use client"

import { OverlayScrollbarsComponent } from "overlayscrollbars-react"
import "overlayscrollbars/overlayscrollbars.css"
import { cn } from "@/lib/utils"

interface DwScrollProps {
  children: React.ReactNode
  /** OS 容器尺寸 class（flex-1 / min-h-0 等），不放布局 class */
  className?: string
  /** 内容区布局 class（flex flex-col gap-* p-* 等） */
  innerClassName?: string
  direction?: "vertical" | "horizontal" | "both"
}

const OS_OPTIONS = {
  vertical: { x: "hidden" as const, y: "scroll" as const },
  horizontal: { x: "scroll" as const, y: "hidden" as const },
  both: { x: "scroll" as const, y: "scroll" as const },
}

export function DwScroll({ children, className, innerClassName, direction = "vertical" }: DwScrollProps) {
  return (
    <OverlayScrollbarsComponent
      element="div"
      className={cn("min-h-0 min-w-0", className)}
      options={{
        scrollbars: {
          theme: "os-theme-dark",
          autoHide: "never",
        },
        overflow: OS_OPTIONS[direction],
      }}
    >
      {innerClassName ? (
        <div className={innerClassName}>{children}</div>
      ) : (
        children
      )}
    </OverlayScrollbarsComponent>
  )
}
