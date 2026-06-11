"use client"

import { useCallback } from "react"
import { HugeiconsIcon } from "@hugeicons/react"
import { Search01Icon, Add01Icon } from "@hugeicons/core-free-icons"

import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { DropdownSelect } from "@/components/ui/select"

const TYPE_OPTIONS = [
  { value: "", label: "全部类型" },
  { value: "SQL", label: "SQL" },
  { value: "SHELL", label: "SHELL" },
]

const STATUS_OPTIONS = [
  { value: "", label: "全部状态" },
  { value: "DRAFT", label: "草稿" },
  { value: "ONLINE", label: "在线" },
]

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

      <DropdownSelect
        value={params.type}
        onChange={(v) => set("type", v)}
        options={TYPE_OPTIONS}
        placeholder="全部类型"
      />

      <DropdownSelect
        value={params.status}
        onChange={(v) => set("status", v)}
        options={STATUS_OPTIONS}
        placeholder="全部状态"
      />

      <div className="flex-1" />

      <Button size="sm" className="h-8" onClick={onNewTask}>
        <HugeiconsIcon icon={Add01Icon} className="size-4" />
        新建任务
      </Button>
    </div>
  )
}
