"use client"

import { Group, Panel, Separator } from "react-resizable-panels"
import { HugeiconsIcon } from "@hugeicons/react"
import { DragDropVerticalIcon } from "@hugeicons/core-free-icons"

import { cn } from "@/lib/utils"

/**
 * 可拖拽分栏（react-resizable-panels v4：Group/Panel/Separator）。
 * 布局持久化用 `useDefaultLayout({ panelIds, storage })`（v4 取代旧 autoSaveId），在消费侧接线。
 */
function ResizablePanelGroup({
  className,
  ...props
}: React.ComponentProps<typeof Group>) {
  return (
    <Group
      data-slot="resizable-panel-group"
      className={cn(
        "flex h-full w-full data-[orientation=vertical]:flex-col",
        className
      )}
      {...props}
    />
  )
}

function ResizablePanel({ ...props }: React.ComponentProps<typeof Panel>) {
  return <Panel data-slot="resizable-panel" {...props} />
}

function ResizableHandle({
  withHandle,
  className,
  ...props
}: React.ComponentProps<typeof Separator> & { withHandle?: boolean }) {
  return (
    <Separator
      data-slot="resizable-handle"
      className={cn(
        "group/rh relative flex w-2 shrink-0 cursor-col-resize items-center justify-center outline-none",
        "after:absolute after:inset-y-0 after:left-1/2 after:w-px after:-translate-x-1/2 after:bg-border after:transition-colors",
        "hover:after:bg-ring/60 focus-visible:after:bg-ring data-[dragging]:after:bg-ring",
        className
      )}
      {...props}
    >
      {withHandle && (
        <span className="z-10 flex h-5 w-3 items-center justify-center rounded-xs border border-border bg-muted text-muted-foreground">
          <HugeiconsIcon icon={DragDropVerticalIcon} className="size-3" />
        </span>
      )}
    </Separator>
  )
}

export { ResizablePanelGroup, ResizablePanel, ResizableHandle }
