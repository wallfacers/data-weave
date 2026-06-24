"use client"

import { forwardRef } from "react"
import {
  OverlayScrollbarsComponent,
  type OverlayScrollbarsComponentRef,
} from "overlayscrollbars-react"
import "overlayscrollbars/overlayscrollbars.css"
import { cn } from "@/lib/utils"

interface DwScrollProps {
  /** OS 容器尺寸 class（flex-1 / min-h-0 等），不放布局 class */
  className?: string
  /** 内容区布局 class（flex flex-col gap-* p-* 等） */
  innerClassName?: string
  direction?: "vertical" | "horizontal" | "both"
  children: React.ReactNode
}

const OS_OPTIONS = {
  vertical: { x: "hidden" as const, y: "scroll" as const },
  horizontal: { x: "scroll" as const, y: "hidden" as const },
  both: { x: "scroll" as const, y: "scroll" as const },
}

/**
 * 项目规范滚动条容器（OverlayScrollbars · os-theme-dark · 4px 中性灰 · 无原生倒三角）。
 * forwardRef 透传 OverlayScrollbarsComponentRef，调用方可经 osInstance().elements().viewport
 * 控制滚动位置（如消息列表「新消息滚到底」）。
 */
export const DwScroll = forwardRef<OverlayScrollbarsComponentRef, DwScrollProps>(
  function DwScroll({ children, className, innerClassName, direction = "vertical" }, ref) {
    return (
      <OverlayScrollbarsComponent
        ref={ref}
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
  },
)
