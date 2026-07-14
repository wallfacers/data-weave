import * as React from "react"

import { cn } from "@/lib/utils"

/**
 * 轻量头像原语（无 Radix 依赖，契合本项目 Base UI 栈）。
 * 监督席消息用途：人类=姓名首字母兜底、Agent=图标插槽。纯展示，中性 token。
 */
function Avatar({ className, ...props }: React.ComponentProps<"span">) {
  return (
    <span
      data-slot="avatar"
      className={cn(
        "relative inline-flex size-7 shrink-0 items-center justify-center overflow-hidden rounded-full bg-muted text-muted-foreground select-none",
        className
      )}
      {...props}
    />
  )
}

function AvatarFallback({ className, ...props }: React.ComponentProps<"span">) {
  return (
    <span
      data-slot="avatar-fallback"
      className={cn("text-[11px] font-medium uppercase leading-none", className)}
      {...props}
    />
  )
}

export { Avatar, AvatarFallback }
