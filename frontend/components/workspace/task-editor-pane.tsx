"use client"

/**
 * 任务编辑子 Tab（data-development-ide D2）：把原侧栏 TaskEditPanel 的配置/参数/替换预览/保存发布
 * 能力迁入主区，脚本区由 <textarea> 升级为 Monaco（CodeEditor，按类型高亮 SQL→sql / SHELL→bash），
 * 并新增「运行」入口——手动触发正式实例（POST /api/tasks/{id}/run）+ 就地流式日志。
 *
 * D8：未发布（DRAFT）时「运行」禁用并提示需先发布，不提供一键发布并运行（发布是有副作用的状态变更）。
 */
import { useCallback, useEffect, useState } from "react"
import { toast } from "sonner"
import { HugeiconsIcon } from "@hugeicons/react"
import { FloppyDiskIcon, RocketIcon } from "@hugeicons/core-free-icons"

import { API_BASE, authFetch, type ApiResponse, type TaskDef } from "@/lib/types"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Badge } from "@/components/ui/badge"
import { DropdownSelect } from "@/components/ui/select"
import { DwScroll } from "@/components/ui/dw-scroll"
import { CodeEditor, type CodeEditorLanguage } from "@/components/code-editor"
import { useEventSource } from "@/lib/workspace/use-event-source"

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

/** /run 闸门返回的 GateResult（前端只关心 outcome / resultInstanceId / actionId）。 */
interface RunResult {
  outcome: "EXECUTED" | "PENDING_APPROVAL" | "REJECTED"
  resultInstanceId?: string | null
  actionId?: number | null
  message?: string
}

export interface TaskEditorPaneProps {
  taskId: number
  /** 保存/发布成功后回调（供 IDE 壳刷新类目树等）。 */
  onSaved?: () => void
}

export function TaskEditorPane({ taskId, onSaved }: TaskEditorPaneProps) {
  const [name, setName] = useState("")
  const [type, setType] = useState("SQL")
  const [content, setContent] = useState("")
  const [description, setDescription] = useState("")
  const [priority, setPriority] = useState(5)
  const [timeoutSec, setTimeoutSec] = useState(60)
  const [retryMax, setRetryMax] = useState(0)
  const [status, setStatus] = useState<string>("DRAFT")
  const [paramRows, setParamRows] = useState<ParamRow[]>([])
  const [saving, setSaving] = useState(false)
  const [loading, setLoading] = useState(true)

  // 替换预览
  const [previewBizDate, setPreviewBizDate] = useState(yesterday())
  const [previewResult, setPreviewResult] = useState<string | null>(null)
  const [previewError, setPreviewError] = useState<string | null>(null)
  const [previewing, setPreviewing] = useState(false)

  // 手动运行
  const [running, setRunning] = useState(false)
  const [runInstanceId, setRunInstanceId] = useState<string | null>(null)

  const published = status === "ONLINE"
  const editorLang: CodeEditorLanguage = type === "SQL" ? "sql" : "bash"

  // 编辑模式：拉取任务数据。GET /api/tasks/{id} 返回 TaskDetail={task, versions}，取 data.task。
  const loadTask = useCallback(async () => {
    setLoading(true)
    try {
      const res = await authFetch(`${API_BASE}/api/tasks/${taskId}`)
      const json = (await res.json()) as ApiResponse<{ task: TaskDef; versions: unknown[] }>
      const t = json.data?.task
      if (!t) return
      setName(t.name ?? "")
      setType(t.type ?? "SQL")
      setContent(t.content ?? "")
      setDescription(t.description ?? "")
      setPriority(t.priority ?? 5)
      setTimeoutSec(t.timeoutSec ?? 60)
      setRetryMax(t.retryMax ?? 0)
      setStatus(t.status ?? "DRAFT")
      setParamRows(parseParams(t.paramsJson))
    } catch {
      toast.error("加载任务失败")
    } finally {
      setLoading(false)
    }
  }, [taskId])

  useEffect(() => {
    loadTask()
  }, [loadTask])

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
      const body = (await res.json()) as ApiResponse<{ content: string }>
      if (body.code !== 0) {
        setPreviewError(body.message ?? "解析失败")
      } else {
        setPreviewResult(body.data?.content ?? "")
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
      await authFetch(`${API_BASE}/api/tasks/${taskId}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      })
      toast.success("已保存草稿")
      onSaved?.()
    } catch {
      toast.error("保存失败")
    } finally {
      setSaving(false)
    }
  }

  async function handlePublish() {
    setSaving(true)
    try {
      const res = await authFetch(`${API_BASE}/api/tasks/${taskId}/publish`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({}),
      })
      const j = (await res.json()) as ApiResponse<TaskDef>
      if (j.code === 0) {
        setStatus("ONLINE")
        toast.success("已发布上线")
        onSaved?.()
      } else {
        toast.error(j.message || "发布失败")
      }
    } catch {
      toast.error("发布失败")
    } finally {
      setSaving(false)
    }
  }

  // 下线：ONLINE → DRAFT（与发布互逆）。后端 TaskController 直调。
  async function handleOffline() {
    setSaving(true)
    try {
      const res = await authFetch(`${API_BASE}/api/tasks/${taskId}/offline`, {
        method: "POST",
      })
      const j = (await res.json()) as ApiResponse<TaskDef>
      if (j.code === 0) {
        setStatus("DRAFT")
        toast.success("已下线")
        onSaved?.()
      } else {
        toast.error(j.message || "下线失败")
      }
    } catch {
      toast.error("下线失败")
    } finally {
      setSaving(false)
    }
  }

  // 手动触发正式实例（manual-run-trigger）：L1 直执行返回 instanceId → 接日志流；
  // 收紧规则则 PENDING_APPROVAL（审批单号），批准后才下发。
  async function handleRun() {
    setRunning(true)
    try {
      const res = await authFetch(`${API_BASE}/api/tasks/${taskId}/run`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({}),
      })
      const j = (await res.json()) as ApiResponse<RunResult>
      if (j.code !== 0 || !j.data) {
        toast.error(j.message || "运行失败")
        return
      }
      const r = j.data
      if (r.outcome === "EXECUTED" && r.resultInstanceId) {
        setRunInstanceId(r.resultInstanceId)
        toast.success("已触发运行")
      } else if (r.outcome === "PENDING_APPROVAL") {
        toast.info(`需审批：单号 ${r.actionId ?? "?"}`)
      } else {
        toast.error(r.message || "运行未执行")
      }
    } catch {
      toast.error("运行失败")
    } finally {
      setRunning(false)
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
    <div className="flex h-full flex-col">
      {/* 工具栏：运行 / 保存 / 发布 + 状态 */}
      <div className="flex flex-wrap items-center gap-2 border-b border-border p-2">
        <Button size="sm" onClick={handleRun} disabled={!published || running}>
          <HugeiconsIcon icon={RocketIcon} className="size-4" />
          {running ? "运行中…" : "运行"}
        </Button>
        {!published && (
          <span className="text-xs text-muted-foreground">
            未发布不可运行，请先
            <button
              type="button"
              className="ml-0.5 underline hover:text-foreground"
              onClick={handlePublish}
              disabled={saving}
            >
              发布
            </button>
          </span>
        )}
        <div className="ml-auto flex items-center gap-2">
          <Badge variant={published ? "success" : "outline"}>
            {published ? "已发布" : "草稿"}
          </Badge>
          <Button size="sm" variant="outline" onClick={handleSave} disabled={saving || !name.trim()}>
            <HugeiconsIcon icon={FloppyDiskIcon} className="size-4" /> 保存草稿
          </Button>
          {published ? (
            <Button size="sm" variant="secondary" onClick={handleOffline} disabled={saving}>
              下线
            </Button>
          ) : (
            <Button size="sm" variant="secondary" onClick={handlePublish} disabled={saving}>
              发布
            </Button>
          )}
        </div>
      </div>

      <div className="flex min-h-0 flex-1">
        {/* 左：代码编辑器（Monaco）+ 运行日志 */}
        <div className="flex min-w-0 flex-1 flex-col">
          <div className="min-h-0 flex-1 p-2">
            <CodeEditor
              value={content}
              onChange={setContent}
              language={editorLang}
              className="h-full"
            />
          </div>
          {runInstanceId && (
            <div className="h-48 shrink-0 border-t border-border">
              <RunLogs instanceId={runInstanceId} />
            </div>
          )}
        </div>

        {/* 右：配置（参数行 / 预览 / 调度）—— 迁自 TaskEditPanel */}
        <DwScroll className="w-72 shrink-0 border-l border-border" innerClassName="flex flex-col gap-3 p-3">
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

          {/* 调度参数：name → expr */}
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
        </DwScroll>
      </div>
    </div>
  )
}

/** 运行日志面板：订阅某实例的 logs/stream，按 runInstanceId 重建（每次运行日志独立）。 */
function RunLogs({ instanceId }: { instanceId: string }) {
  const { events, connected, error } = useEventSource(
    `${API_BASE}/api/ops/instances/${instanceId}/logs/stream`,
  )
  const lines = events.filter((e) => e.type === "log").map((e) => e.data)
  const ended = events.some((e) => e.type === "end")
  const connLabel = connected ? "实时" : ended ? "已结束" : error ? "断开" : "连接中"
  const connVariant = connected ? "success" : error ? "destructive" : "info"

  return (
    <div className="flex h-full flex-col">
      <div className="flex h-6 shrink-0 items-center gap-2 px-3 text-[10px] text-muted-foreground">
        <span className="font-mono">{instanceId.slice(0, 13)}…</span>
        <Badge variant={connVariant as "success" | "destructive" | "info"} className="h-3.5 px-1 text-[9px]">
          {connLabel}
        </Badge>
      </div>
      <DwScroll className="flex-1" innerClassName="px-3 pb-2 font-mono text-xs leading-relaxed">
        {lines.length === 0 ? (
          <div className="text-muted-foreground">
            {connected ? "等待日志输出…" : ended ? "无日志记录" : "连接中…"}
          </div>
        ) : (
          <div className="space-y-px">
            {lines.map((line, i) => (
              <div key={i} className="whitespace-pre-wrap break-all">
                {line}
              </div>
            ))}
          </div>
        )}
      </DwScroll>
    </div>
  )
}
