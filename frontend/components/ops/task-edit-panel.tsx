"use client"

import { useEffect, useState } from "react"

import { DwScroll } from "@/components/ui/dw-scroll"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { DropdownSelect } from "@/components/ui/select"
import { type TaskDef, API_BASE, authFetch } from "@/lib/types"

const TYPE_OPTIONS = [
  { value: "SQL", label: "SQL" },
  { value: "SHELL", label: "SHELL" },
]

export interface SidePanelViewProps {
  params?: Record<string, unknown>
  onClose: () => void
}

export function TaskEditPanel({ params, onClose }: SidePanelViewProps) {
  const taskId = params?.taskId as number | undefined

  const [name, setName] = useState("")
  const [type, setType] = useState("SQL")
  const [content, setContent] = useState("")
  const [description, setDescription] = useState("")
  const [priority, setPriority] = useState(5)
  const [timeoutSec, setTimeoutSec] = useState(60)
  const [retryMax, setRetryMax] = useState(0)
  const [saving, setSaving] = useState(false)
  const [loading, setLoading] = useState(!!taskId)

  // 编辑模式：拉取任务数据
  useEffect(() => {
    if (!taskId) return
    setLoading(true)
    authFetch(`${API_BASE}/api/tasks/${taskId}`)
      .then((res) => res.json())
      .then((t: TaskDef) => {
        setName(t.name)
        setType(t.type)
        setContent(t.content ?? "")
        setDescription(t.description ?? "")
        setPriority(t.priority ?? 5)
        setTimeoutSec(t.timeoutSec ?? 60)
        setRetryMax(t.retryMax ?? 0)
      })
      .catch(() => onClose())
      .finally(() => setLoading(false))
  }, [taskId, onClose])

  async function handleSave() {
    if (!name.trim()) return
    setSaving(true)
    try {
      const body = { name, type, content, description, priority, timeoutSec, retryMax }
      if (taskId) {
        await authFetch(`${API_BASE}/api/tasks/${taskId}`, {
          method: "PUT",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(body),
        })
      } else {
        await authFetch(`${API_BASE}/api/tasks`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(body),
        })
      }
      onClose()
    } finally {
      setSaving(false)
    }
  }

  async function handlePublish() {
    if (!taskId) return
    setSaving(true)
    try {
      await authFetch(`${API_BASE}/api/tasks/${taskId}/publish`, { method: "POST" })
      onClose()
    } finally {
      setSaving(false)
    }
  }

  if (loading) {
    return (
      <div className="flex flex-1 items-center justify-center text-sm text-muted-foreground">
        加载中…
      </div>
    )
  }

  return (
    <DwScroll className="flex-1" innerClassName="flex flex-col gap-3 p-4">
      <div className="flex flex-col gap-1.5">
        <label className="text-xs font-medium text-muted-foreground">名称</label>
        <Input className="h-8 text-sm" value={name} onChange={(e) => setName(e.target.value)} placeholder="任务名称" />
      </div>

      <div className="flex gap-3">
        <div className="flex flex-1 flex-col gap-1.5">
          <label className="text-xs font-medium text-muted-foreground">类型</label>
          <DropdownSelect value={type} onChange={setType} options={TYPE_OPTIONS} />
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

      <div className="mt-auto flex gap-2 pt-4">
        <Button size="sm" onClick={handleSave} disabled={saving || !name.trim()}>
          {saving ? "保存中…" : "保存草稿"}
        </Button>
        {taskId && (
          <Button size="sm" variant="secondary" onClick={handlePublish} disabled={saving}>
            保存并发布
          </Button>
        )}
      </div>
    </DwScroll>
  )
}
