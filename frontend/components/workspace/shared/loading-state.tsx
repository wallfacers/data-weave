"use client"

import { HugeiconsIcon } from "@hugeicons/react"
import { RefreshIcon } from "@hugeicons/core-free-icons"

export function LoadingState() {
  return (
    <div className="flex flex-col items-center justify-center gap-2 py-12 text-muted-foreground">
      <HugeiconsIcon icon={RefreshIcon} className="size-5 animate-spin" />
      <span className="text-xs">Loading…</span>
    </div>
  )
}
