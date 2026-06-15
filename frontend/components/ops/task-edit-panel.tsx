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

// 快捷表达式预设：点选即填入当前参数的「表达式」，免去手敲 ${...}
const PRESET_PLACEHOLDER = "__preset__"
const EXPRESSION_PRESETS = [
  { value: PRESET_PLACEHOLDER, label: "插入常用…" },
  { value: "${yyyymmdd}", label: "业务日期 yyyymmdd" },
  { value: "${yyyymmdd-1}", label: "前一天" },
  { value: "${yyyymmdd-7*1}", label: "前 7 天" },
  { value: "${yyyy-mm-dd}", label: "业务日期 yyyy-mm-dd" },
  { value: "${yyyymm}", label: "业务月 yyyymm" },
  { value: "${yyyymm-1}", label: "上月" },
  { value: "${yyyy-1}", label: "去年" },
  { value: "$bizdate", label: "$bizdate（业务日）" },
  { value: "$bizmonth", label: "$bizmonth（业务月）" },
  { value: "$gmtdate", label: "$gmtdate（今天）" },
]

interface ParamRow {
  name: string
  expr: string
}

function parseParams(json: string | null): ParamRow[] {
  if (!json) return []
  try {
    const obj = JSON.parse(json)
    if (obj && typeof obj === "object") {
      return Object.entries(obj).map(([name, expr]) => ({ name, expr: String(expr ?? "") }))
    }
  } catch {
    /* 非法 JSON 视作空 */
  }
  return []
}

function serializeParams(rows: ParamRow[]): string {
  const obj: Record<string, string> = {}
  for (const r of rows) {
    if (r.name.trim()) obj[r.name.trim()] = r.expr
  }
  return Object.keys(obj).length ? JSON.stringify(obj) : ""
}

function yesterday(): string {
  const d = new Date()
  d.setDate(d.getDate() - 1)
  return d.toISOString().slice(0, 10)
}

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
  const [paramRows, setParamRows] = useState<ParamRow[]>([])
  const [saving, setSaving] = useState(false)
  const [loading, setLoading] = useState(!!taskId)

  // 替换预览
  const [previewBizDate, setPreviewBizDate] = useState(yesterday())
  const [previewResult, setPreviewResult] = useState<string | null>(null)
  const [previewError, setPreviewError] = useState<string | null>(null)
  const [previewing, setPreviewing] = useState(false)

  // 编辑模式：拉取任务数据
  useEffect(() => {
    if (!taskId) return
    setLoading(true)
    authFetch(`${API_BASE}/api/tasks/${taskId}`)
      .then((res) => res.json())
      .then((json: { data?: TaskDef } & TaskDef) => {
        const t = (json.data ?? json) as TaskDef
        setName(t.name)
        setType(t.type)
        setContent(t.content ?? "")
        setDescription(t.description ?? "")
        setPriority(t.priority ?? 5)
        setTimeoutSec(t.timeoutSec ?? 60)
        setRetryMax(t.retryMax ?? 0)
        setParamRows(parseParams(t.paramsJson))
      })
      .catch(() => onClose())
      .finally(() => setLoading(false))
  }, [taskId, onClose])

  async function handlePreview() {
    setPreviewing(true)
    setPreviewError(null)
    setPreviewResult(null)
    try {
      const res = await authFetch(`${API_BASE}/api/tasks/preview-params`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          content,
          bizDate: previewBizDate,
          paramsJson: serializeParams(paramRows) || null,
        }),
      })
      const body = await res.json()
      if (body?.code !== 0) {
        setPreviewError(body?.message ?? "解析失败")
      } else {
        setPreviewResult(body?.data?.content ?? "")
      }
    } catch (e) {
      setPreviewError(String(e))
    } finally {
      setPreviewing(false)
    }
  }

  async function handleSave() {
    if (!name.trim()) return
    setSaving(true)
    try {
      const paramsJson = serializeParams(paramRows)
      const body = { name, type, content, description, priority, timeoutSec, retryMax, paramsJson: paramsJson || null }
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

  function addParam() {
    setParamRows((rs) => [...rs, { name: "", expr: "" }])
  }
  function updateParam(idx: number, patch: Partial<ParamRow>) {
    setParamRows((rs) => rs.map((r, i) => (i === idx ? { ...r, ...patch } : r)))
  }
  function removeParam(idx: number) {
    setParamRows((rs) => rs.filter((_, i) => i !== idx))
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
          placeholder={type === "SQL" ? "SELECT ... WHERE dt = ${yyyymmdd-1}" : "#!/bin/bash\necho ${yyyymmdd}"}
        />
      </div>

      {/* 调度参数：name → expr，免手敲表达式 */}
      <div className="flex flex-col gap-1.5">
        <div className="flex items-center justify-between">
          <label className="text-xs font-medium text-muted-foreground">调度参数（可选）</label>
          <Button size="sm" variant="ghost" className="h-6 px-2 text-xs" onClick={addParam}>+ 添加</Button>
        </div>
        <p className="text-[11px] leading-relaxed text-muted-foreground">
          在内容里用 <code className="font-mono">{"${参数名}"}</code> 引用；参数值也可填表达式（如 <code className="font-mono">{"${yyyymmdd-1}"}</code>），会递归展开。留空即不启用。
        </p>
        {paramRows.length === 0 ? (
          <div className="rounded-md border border-dashed border-input px-3 py-2 text-xs text-muted-foreground">
            暂无参数，点「+ 添加」配置。
          </div>
        ) : (
          <div className="flex flex-col gap-2">
            {paramRows.map((row, idx) => (
              <div key={idx} className="flex flex-col gap-1.5 rounded-md border border-input p-2">
                <div className="flex gap-1.5">
                  <Input
                    className="h-7 flex-1 text-xs font-mono"
                    value={row.name}
                    onChange={(e) => updateParam(idx, { name: e.target.value })}
                    placeholder="参数名"
                  />
                  <Button size="sm" variant="ghost" className="h-7 px-2 text-xs text-muted-foreground" onClick={() => removeParam(idx)}>删除</Button>
                </div>
                <Input
                  className="h-7 flex-1 text-xs font-mono"
                  value={row.expr}
                  onChange={(e) => updateParam(idx, { expr: e.target.value })}
                  placeholder="表达式，如 ${yyyymmdd-1}"
                />
                <DropdownSelect
                  value={PRESET_PLACEHOLDER}
                  onChange={(v) => {
                    if (v !== PRESET_PLACEHOLDER) updateParam(idx, { expr: v })
                  }}
                  options={EXPRESSION_PRESETS}
                />
              </div>
            ))}
          </div>
        )}
      </div>

      {/* 替换预览 */}
      <div className="flex flex-col gap-1.5">
        <label className="text-xs font-medium text-muted-foreground">替换预览</label>
        <div className="flex gap-1.5">
          <Input
            className="h-7 flex-1 text-xs font-mono"
            value={previewBizDate}
            onChange={(e) => setPreviewBizDate(e.target.value)}
            placeholder="业务日期 yyyy-MM-dd"
          />
          <Button size="sm" variant="secondary" className="h-7" onClick={handlePreview} disabled={previewing || !content}>
            {previewing ? "…" : "预览"}
          </Button>
        </div>
        {previewError && <p className="text-[11px] text-destructive">{previewError}</p>}
        {previewResult !== null && (
          <pre className="max-h-[140px] overflow-auto rounded-md border border-input bg-muted/30 p-2 font-mono text-[11px] whitespace-pre-wrap break-all">
            {previewResult}
          </pre>
        )}
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
