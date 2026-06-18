"use client"

/**
 * 任务编辑子 Tab（data-development-ide D2）：把原侧栏 TaskEditPanel 的配置/参数/替换预览/保存发布
 * 能力迁入主区，脚本区由 <textarea> 升级为 Monaco（CodeEditor，按类型高亮 SQL→sql / SHELL→bash），
 * 并新增「运行」入口——手动触发正式实例（POST /api/tasks/{id}/run）+ 就地流式日志。
 *
 * D8：未发布（DRAFT）时「运行」禁用并提示需先发布，不提供一键发布并运行（发布是有副作用的状态变更）。
 */
import { useCallback, useEffect, useMemo, useState } from "react"
import { toast } from "sonner"
import { useTranslations } from "next-intl"
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
import { useCatalogTreeStore } from "@/lib/workspace/catalog-tree-store"

const TYPE_OPTIONS = [
  { value: "SQL", label: "SQL" },
  { value: "SHELL", label: "SHELL" },
]

// 快捷表达式预设：点选即填入当前参数的「表达式」，免去手敲 ${...}。
// value 为表达式字面量（不翻译），label 经 i18n 在组件内生成。
const PRESET_PLACEHOLDER = "__preset__"

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
}

export function TaskEditorPane({ taskId }: TaskEditorPaneProps) {
  const t = useTranslations()
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

  // 快捷表达式预设：value 固定，label 经 i18n。
  const expressionPresets = useMemo(
    () => [
      { value: PRESET_PLACEHOLDER, label: t("taskEditor.presetInsert") },
      { value: "${yyyymmdd}", label: t("taskEditor.presetYmd") },
      { value: "${yyyymmdd-1}", label: t("taskEditor.presetYmdMinus1") },
      { value: "${yyyymmdd-7*1}", label: t("taskEditor.presetYmdMinus7") },
      { value: "${yyyy-mm-dd}", label: t("taskEditor.presetYmdDash") },
      { value: "${yyyymm}", label: t("taskEditor.presetYm") },
      { value: "${yyyymm-1}", label: t("taskEditor.presetYmMinus1") },
      { value: "${yyyy-1}", label: t("taskEditor.presetYMinus1") },
      { value: "$bizdate", label: t("taskEditor.presetBizdate") },
      { value: "$bizmonth", label: t("taskEditor.presetBizmonth") },
      { value: "$gmtdate", label: t("taskEditor.presetGmtdate") },
    ],
    [t],
  )

  // 编辑模式：拉取任务数据。GET /api/tasks/{id} 返回 TaskDetail={task, versions}，取 data.task。
  const loadTask = useCallback(async () => {
    setLoading(true)
    try {
      const res = await authFetch(`${API_BASE}/api/tasks/${taskId}`)
      const json = (await res.json()) as ApiResponse<{ task: TaskDef; versions: unknown[] }>
      const td = json.data?.task
      if (!td) return
      setName(td.name ?? "")
      setType(td.type ?? "SQL")
      setContent(td.content ?? "")
      setDescription(td.description ?? "")
      setPriority(td.priority ?? 5)
      setTimeoutSec(td.timeoutSec ?? 60)
      setRetryMax(td.retryMax ?? 0)
      setStatus(td.status ?? "DRAFT")
      setParamRows(parseParams(td.paramsJson))
    } catch {
      toast.error(t("taskEditor.toastLoadFailed"))
    } finally {
      setLoading(false)
    }
  }, [taskId, t])

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
        setPreviewError(body.message ?? t("taskEditor.parseFailed"))
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
      toast.success(t("taskEditor.toastSaved"))
      useCatalogTreeStore.getState().updateTask(taskId, {
        name, type, content, description, priority, timeoutSec, retryMax,
        paramsJson: paramsJson || null,
      })
    } catch {
      toast.error(t("taskEditor.toastSaveFailed"))
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
      if (j.code === 0 && j.data) {
        setStatus("ONLINE")
        toast.success(t("taskEditor.toastPublished"))
        useCatalogTreeStore.getState().updateTask(taskId, {
          status: "ONLINE",
          currentVersionNo: j.data.currentVersionNo,
        })
      } else {
        toast.error(j.message || t("taskEditor.toastPublishFailed"))
      }
    } catch {
      toast.error(t("taskEditor.toastPublishFailed"))
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
        toast.success(t("taskEditor.toastOffline"))
        useCatalogTreeStore.getState().updateTask(taskId, { status: "DRAFT" })
      } else {
        toast.error(j.message || t("taskEditor.toastOfflineFailed"))
      }
    } catch {
      toast.error(t("taskEditor.toastOfflineFailed"))
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
        toast.error(j.message || t("taskEditor.toastRunFailed"))
        return
      }
      const r = j.data
      if (r.outcome === "EXECUTED" && r.resultInstanceId) {
        setRunInstanceId(r.resultInstanceId)
        toast.success(t("taskEditor.toastRunTriggered"))
      } else if (r.outcome === "PENDING_APPROVAL") {
        toast.info(t("taskEditor.toastNeedApproval", { id: r.actionId ?? "?" }))
      } else {
        toast.error(r.message || t("taskEditor.toastRunNotExecuted"))
      }
    } catch {
      toast.error(t("taskEditor.toastRunFailed"))
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
        {t("common.loading")}
      </div>
    )
  }

  return (
    <div className="flex h-full flex-col">
      {/* 工具栏：运行 / 保存 / 发布 + 状态 */}
      <div className="flex flex-wrap items-center gap-2 border-b border-border p-2">
        <Button size="sm" onClick={handleRun} disabled={!published || running}>
          <HugeiconsIcon icon={RocketIcon} className="size-4" />
          {running ? t("taskEditor.running") : t("taskEditor.run")}
        </Button>
        {!published && (
          <span className="text-xs text-muted-foreground">
            {t("taskEditor.notPublishedHint")}
            <button
              type="button"
              className="ml-0.5 underline hover:text-foreground"
              onClick={handlePublish}
              disabled={saving}
            >
              {t("taskEditor.publish")}
            </button>
          </span>
        )}
        <div className="ml-auto flex items-center gap-2">
          <Badge variant={published ? "success" : "outline"}>
            {published ? t("taskEditor.statusOnline") : t("taskEditor.statusDraft")}
          </Badge>
          <Button size="sm" variant="outline" onClick={handleSave} disabled={saving || !name.trim()}>
            <HugeiconsIcon icon={FloppyDiskIcon} className="size-4" /> {t("taskEditor.saveDraft")}
          </Button>
          {published ? (
            <Button size="sm" variant="secondary" onClick={handleOffline} disabled={saving}>
              {t("taskEditor.offline")}
            </Button>
          ) : (
            <Button size="sm" variant="secondary" onClick={handlePublish} disabled={saving}>
              {t("taskEditor.publish")}
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
            <label className="text-xs font-medium text-muted-foreground">{t("taskEditor.name")}</label>
            <Input className="h-8 text-sm" value={name} onChange={(e) => setName(e.target.value)} placeholder={t("taskEditor.namePlaceholder")} />
          </div>

          <div className="flex gap-3">
            <div className="flex flex-1 flex-col gap-1.5">
              <label className="text-xs font-medium text-muted-foreground">{t("taskEditor.type")}</label>
              <DropdownSelect value={type} onChange={setType} options={TYPE_OPTIONS} />
            </div>
            <div className="flex flex-1 flex-col gap-1.5">
              <label className="text-xs font-medium text-muted-foreground">{t("taskEditor.priority")}</label>
              <Input className="h-8 text-sm" type="number" min={0} max={9} value={priority} onChange={(e) => setPriority(Number(e.target.value))} />
            </div>
          </div>

          <div className="flex flex-col gap-1.5">
            <label className="text-xs font-medium text-muted-foreground">{t("taskEditor.description")}</label>
            <Input className="h-8 text-sm" value={description} onChange={(e) => setDescription(e.target.value)} placeholder={t("taskEditor.descriptionPlaceholder")} />
          </div>

          {/* 调度参数：name → expr */}
          <div className="flex flex-col gap-1.5">
            <div className="flex items-center justify-between">
              <label className="text-xs font-medium text-muted-foreground">{t("taskEditor.params")}</label>
              <Button size="sm" variant="ghost" className="h-6 px-2 text-xs" onClick={addParam}>{t("taskEditor.addParam")}</Button>
            </div>
            <p className="text-[11px] leading-relaxed text-muted-foreground">
              {t.rich("taskEditor.paramsHint", {
                code: (chunks) => <code className="font-mono">{chunks}</code>,
              })}
            </p>
            {paramRows.length === 0 ? (
              <div className="rounded-md border border-dashed border-input px-3 py-2 text-xs text-muted-foreground">
                {t("taskEditor.noParams")}
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
                        placeholder={t("taskEditor.paramName")}
                      />
                      <Button size="sm" variant="ghost" className="h-7 px-2 text-xs text-muted-foreground" onClick={() => removeParam(idx)}>{t("common.delete")}</Button>
                    </div>
                    <Input
                      className="h-7 flex-1 text-xs font-mono"
                      value={row.expr}
                      onChange={(e) => updateParam(idx, { expr: e.target.value })}
                      placeholder={t("taskEditor.exprPlaceholder")}
                    />
                    <DropdownSelect
                      value={PRESET_PLACEHOLDER}
                      onChange={(v) => {
                        if (v !== PRESET_PLACEHOLDER) updateParam(idx, { expr: v })
                      }}
                      options={expressionPresets}
                    />
                  </div>
                ))}
              </div>
            )}
          </div>

          {/* 替换预览 */}
          <div className="flex flex-col gap-1.5">
            <label className="text-xs font-medium text-muted-foreground">{t("taskEditor.preview")}</label>
            <div className="flex gap-1.5">
              <Input
                className="h-7 flex-1 text-xs font-mono"
                value={previewBizDate}
                onChange={(e) => setPreviewBizDate(e.target.value)}
                placeholder={t("taskEditor.bizDatePlaceholder")}
              />
              <Button size="sm" variant="secondary" className="h-7" onClick={handlePreview} disabled={previewing || !content}>
                {previewing ? "…" : t("taskEditor.previewBtn")}
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
              <label className="text-xs font-medium text-muted-foreground">{t("taskEditor.timeout")}</label>
              <Input className="h-8 text-sm" type="number" value={timeoutSec} onChange={(e) => setTimeoutSec(Number(e.target.value))} />
            </div>
            <div className="flex flex-1 flex-col gap-1.5">
              <label className="text-xs font-medium text-muted-foreground">{t("taskEditor.retryMax")}</label>
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
  const t = useTranslations()
  const { events, connected, error } = useEventSource(
    `${API_BASE}/api/ops/instances/${instanceId}/logs/stream`,
  )
  const lines = events.filter((e) => e.type === "log").map((e) => e.data)
  const ended = events.some((e) => e.type === "end")
  const connLabel = connected
    ? t("taskEditor.logLive")
    : ended
      ? t("taskEditor.logEnded")
      : error
        ? t("taskEditor.logDisconnected")
        : t("taskEditor.logConnecting")
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
            {connected
              ? t("taskEditor.logWaiting")
              : ended
                ? t("taskEditor.logNoRecords")
                : t("taskEditor.logConnectingShort")}
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
