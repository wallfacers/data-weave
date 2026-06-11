"use client"

import { SqlWorkbench } from "@/components/sql-workbench"

export function SqlWorkbenchView() {
  return (
    <div className="flex flex-1 flex-col overflow-hidden">
      <SqlWorkbench />
    </div>
  )
}
