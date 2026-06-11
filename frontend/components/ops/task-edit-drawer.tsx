"use client"

import { useEffect, useState } from "react"

import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
  SheetFooter,
} from "@/components/ui/sheet"
import { type TaskDef, API_BASE } from "@/lib/types"

interface TaskEditDrawerProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  task: TaskDef | null // null = create mode
  onSaved: () => void
}

export function TaskEditDrawer({ open, onOpenChange, task, onSaved }: TaskEditDrawerProps) {
  const [name, setName] = useState("")
  const [type, setType] = useState("SQL")
  const [content, setContent] = useState("")
  const [description, setDescription] = useState("")
  const [priority, setPriority] = useState(5)
  const [timeoutSec, setTimeoutSec] = useState(60)
  const [retryMax, setRetryMax] = useState(0)
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    if (task) {
      setName(task.name)
      setType(task.type)
      setContent(task.content ?? "")
      setDescription(task.description ?? "")
      setPriority(task.priority ?? 5)
      setTimeoutSec(task.timeoutSec ?? 60)
      setRetryMax(task.retryMax ?? 0)
    } else {
      setName("")
      setType("SQL")
      setContent("")
      setDescription("")
      setPriority(5)
      setTimeoutSec(60)
      setRetryMax(0)
    }
  }, [task, open])

  async function handleSave() {
    if (!name.trim()) return
    setSaving(true)
    try {
      const body = { name, type, content, description, priority, timeoutSec, retryMax }
      if (task) {
        await fetch(`${API_BASE}/api/tasks/${task.id}`, {
          method: "PUT",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(body),
        })
      } else {
        await fetch(`${API_BASE}/api/tasks`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(body),
        })
      }
      onOpenChange(false)
      onSaved()
    } finally {
      setSaving(false)
    }
  }

  async function handlePublish() {
    if (!task) return
    setSaving(true)
    try {
      await fetch(`${API_BASE}/api/tasks/${task.id}/publish`, { method: "POST" })
      onOpenChange(false)
      onSaved()
    } finally {
      setSaving(false)
    }
  }

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent className="flex w-[480px] flex-col gap-4 sm:max-w-[480px]">
        <SheetHeader>
          <SheetTitle>{task ? `编辑任务 #${task.id}` : "新建任务"}</SheetTitle>
        </SheetHeader>

        <div className="flex flex-1 flex-col gap-3 overflow-auto px-1">
          <div className="flex flex-col gap-1.5">
            <label className="text-xs font-medium text-muted-foreground">名称</label>
            <Input className="h-8 text-sm" value={name} onChange={(e) => setName(e.target.value)} placeholder="任务名称" />
          </div>

          <div className="flex gap-3">
            <div className="flex flex-1 flex-col gap-1.5">
              <label className="text-xs font-medium text-muted-foreground">类型</label>
              <select className="h-8 rounded-md border border-input bg-background px-2 text-sm" value={type} onChange={(e) => setType(e.target.value)}>
                <option value="SQL">SQL</option>
                <option value="SHELL">SHELL</option>
              </select>
            </div>
            <div className="flex flex-1 flex-col gap-1.5">
              <label className="text-xs font-medium text-muted-foreground">优先级</label>
              <Input className="h-8 text-sm" type="number" min={0} max={9} value={priority} onChange={(e) => setPriority(Number(e.target.value))} />
            </div>
          </div>

          <div className="flex flex-col gap-1.5">
            <label className="text-xs font-medium text-muted-foreground">描述</label>
            <Input className="h-8 text-sm" value={description} onChange={(e) => setDescription(e.target.value)} placeholder="任务描述（可选）" />
          </div>

          <div className="flex flex-col gap-1.5">
            <label className="text-xs font-medium text-muted-foreground">{type === "SQL" ? "SQL 内容" : "脚本内容"}</label>
            <textarea
              className="flex min-h-[160px] w-full rounded-md border border-input bg-background px-3 py-2 font-mono text-sm outline-none placeholder:text-muted-foreground focus-visible:ring-1 focus-visible:ring-ring"
              value={content}
              onChange={(e) => setContent(e.target.value)}
              placeholder={type === "SQL" ? "SELECT ..." : "#!/bin/bash\n..."}
            />
          </div>

          <div className="flex gap-3">
            <div className="flex flex-1 flex-col gap-1.5">
              <label className="text-xs font-medium text-muted-foreground">超时（秒）</label>
              <Input className="h-8 text-sm" type="number" value={timeoutSec} onChange={(e) => setTimeoutSec(Number(e.target.value))} />
            </div>
            <div className="flex flex-1 flex-col gap-1.5">
              <label className="text-xs font-medium text-muted-foreground">最大重试</label>
              <Input className="h-8 text-sm" type="number" min={0} value={retryMax} onChange={(e) => setRetryMax(Number(e.target.value))} />
            </div>
          </div>
        </div>

        <SheetFooter className="flex gap-2">
          <Button variant="outline" size="sm" onClick={() => onOpenChange(false)}>取消</Button>
          <Button size="sm" onClick={handleSave} disabled={saving || !name.trim()}>
            {saving ? "保存中…" : "保存草稿"}
          </Button>
          {task && task.status === "DRAFT" && (
            <Button size="sm" variant="secondary" onClick={handlePublish} disabled={saving}>
              保存并发布
            </Button>
          )}
        </SheetFooter>
      </SheetContent>
    </Sheet>
  )
}
