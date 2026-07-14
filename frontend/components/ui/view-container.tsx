import * as React from "react"

import { cn } from "@/lib/utils"

/**
 * 视图根容器 —— 统一「视图外边距」节奏的唯一真相源，与 Card 的 --card-spacing 对称。
 *
 * 四周留白一律走 --view-spacing token（p-(--view-spacing)），default=20px（spacing 刻度 5）；
 * 密集视图（指挥台、满幅画布）传 density="compact" → data-density=compact → 10px（spacing 刻度 2.5）。
 * 禁止在视图根手填 p-4/p-5/p-2.5 等字面值；紧凑靠 density 变体，不另写数值。
 *
 * 根为可滚动区（DwScroll）的视图无法直接用本组件：改在 DwScroll 的 innerClassName 上写
 * p-(--view-spacing)（token 全局可用），并在需要紧凑时于外层容器加 data-density="compact"。
 * 见 DESIGN.md「间距系统」。
 */
function ViewContainer({
  className,
  density = "default",
  ...props
}: React.ComponentProps<"div"> & { density?: "default" | "compact" }) {
  return (
    <div
      data-slot="view-container"
      data-density={density}
      className={cn(
        "flex min-h-0 min-w-0 flex-1 flex-col p-(--view-spacing)",
        className
      )}
      {...props}
    />
  )
}

export { ViewContainer }
