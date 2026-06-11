"use client"

import { useCallback } from "react"
import { HugeiconsIcon } from "@hugeicons/react"
import { Search01Icon, Add01Icon } from "@hugeicons/core-free-icons"

import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"

export interface TaskSearchParams {
  keyword: string
  type: string
  status: string
}

interface TaskSearchBarProps {
  params: TaskSearchParams
  onChange: (params: TaskSearchParams) => void
  onNewTask: () => void
}

export function TaskSearchBar({ params, onChange, onNewTask }: TaskSearchBarProps) {
  const set = useCallback(
    (key: keyof TaskSearchParams, value: string) => {
      onChange({ ...params, [key]: value })
    },
    [params, onChange],
  )

  return (
    <div className="flex flex-wrap items-center gap-2">
      <div className="relative">
        <HugeiconsIcon
          icon={Search01Icon}
          className="pointer-events-none absolute left-2.5 top-1/2 size-3.5 -translate-y-1/2 text-muted-foreground"
        />
        <Input
          className="h-8 w-52 pl-8 text-sm"
          placeholder="搜索任务名称…"
          value={params.keyword}
          onChange={(e: React.ChangeEvent<HTMLInputElement>) => set("keyword", e.target.value)}
        />
      </div>

      <select
        className="h-8 rounded-md border border-input bg-background px-2 text-sm text-foreground outline-none"
        value={params.type}
        onChange={(e) => set("type", e.target.value)}
      >
        <option value="">全部类型</option>
        <option value="SQL">SQL</option>
        <option value="SHELL">SHELL</option>
      </select>

      <select
        className="h-8 rounded-md border border-input bg-background px-2 text-sm text-foreground outline-none"
        value={params.status}
        onChange={(e) => set("status", e.target.value)}
      >
        <option value="">全部状态</option>
        <option value="DRAFT">草稿</option>
        <option value="ONLINE">在线</option>
      </select>

      <div className="flex-1" />

      <Button size="sm" className="h-8" onClick={onNewTask}>
        <HugeiconsIcon icon={Add01Icon} className="size-4" />
        新建任务
      </Button>
    </div>
  )
}
