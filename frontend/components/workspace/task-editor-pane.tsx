"use client"

/**
 * 任务编辑子 Tab（data-development-ide D2 + task-run-decouple）：配置/参数/替换预览/保存发布 + 运行。
 * 脚本区为 Monaco（CodeEditor，按类型高亮 SQL→sql / SHELL→bash）。
 *
 * 运行（task-run-decouple）：不再要求先发布——已发布起 NORMAL 正式实例、未发布起 TEST 测试实例，
 * 测试运行携带编辑器**当前内容（含未保存改动）**经 `/run` 下发（不落 task_def）。
 * 编辑器维护 dirty 脏态（保存草稿→「● 待保存」），发布按钮按 `hasDraftChange` 启用且与保存按钮同风格。
 * 运行日志为 Tabs 容器：每次运行一个日志 tab（命名=任务名+运行时间），DataWorks 风滚屏，预留结果集 tab 位。
 */
import { useCallback, useEffect, useMemo, useState } from "react"
import { toast } from "sonner"
import { useLocale, useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import { FloppyDiskIcon, RocketIcon } from "@hugeicons/core-free-icons"

import { API_BASE, authFetch, type ApiResponse, type TaskDef } from "@/lib/types"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Badge } from "@/components/ui/badge"
import { DropdownSelect } from "@/components/ui/select"
import { DwScroll } from "@/components/ui/dw-scroll"
import { CodeEditor, type CodeEditorLanguage } from "@/components/code-editor"
import { RunLogsTabs, useRunLogTabs, type RunTab } from "@/components/workspace/run-logs-tabs"
import { useCatalogTreeStore } from "@/lib/workspace/catalog-tree-store"
import { useWorkspaceStore } from "@/lib/workspace/store"
import { cn } from "@/lib/utils"

const TYPE_OPTIONS = [
  { value: "SQL", label: "SQL" },
  { value: "SHELL", label: "SHELL" },
]

// 快捷表达式预设：点选即填入当前参数的「表达式」，免去手敲 ${...}。
// value 为表达式字面量（不翻译），label 经 i18n 在组件内生成。
const PRESET_PLACEHOLDER = "__preset__"

// 运行日志区高度持久化键（高度逻辑在 useRunLogTabs 内）
const LOG_HEIGHT_KEY = "dw.taskEditor.logHeight"

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
  const locale = useLocale()
  const [name, setName] = useState("")
  const [type, setType] = useState("SQL")
  const [content, setContent] = useState("")
  const [description, setDescription] = useState("")
  const [priority, setPriority] = useState(5)
  const [timeoutSec, setTimeoutSec] = useState(60)
  const [retryMax, setRetryMax] = useState(0)
  const [status, setStatus] = useState<string>("DRAFT")
  const [hasDraft, setHasDraft] = useState(false)
  const [dirty, setDirty] = useState(false)
  const [paramRows, setParamRows] = useState<ParamRow[]>([])
  const [saving, setSaving] = useState(false)
  const [loading, setLoading] = useState(true)

  // 替换预览
  const [previewBizDate, setPreviewBizDate] = useState(yesterday())
  const [previewResult, setPreviewResult] = useState<string | null>(null)
  const [previewError, setPreviewError] = useState<string | null>(null)
  const [previewing, setPreviewing] = useState(false)

  // 手动运行 + 日志 Tabs（状态/关闭族/拖拽高度收口到公共 hook）
  const [running, setRunning] = useState(false)
  const {
    runTabs,
    activeRunTab,
    setActiveRunTab,
    logHeight,
    onLogResizeDown,
    openRunTab,
    closeRunTab,
    closeOtherRunTabs,
    closeRunTabsRight,
    closeRunTabsLeft,
    closeAllRunTabs,
  } = useRunLogTabs(LOG_HEIGHT_KEY)

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
      setHasDraft((td.hasDraftChange ?? 0) > 0)
      setParamRows(parseParams(td.paramsJson))
      setDirty(false) // 加载完成即干净态——后续用户编辑才置脏
    } catch {
      toast.error(t("taskEditor.toastLoadFailed"))
    } finally {
      setLoading(false)
    }
  }, [taskId, t])

  useEffect(() => {
    loadTask()
  }, [loadTask])

  // 同步 dirty 状态到 workspace store（供类目树/Tab 栏显示黄点 + 关闭拦截）
  useEffect(() => {
    useWorkspaceStore.getState().setTaskDirty(taskId, dirty)
    return () => {
      // 组件卸载时清理
      useWorkspaceStore.getState().setTaskDirty(taskId, false)
    }
  }, [taskId, dirty])

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
      setDirty(false)
      setHasDraft(true) // 保存后即有「未发布改动」，发布按钮可用
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
        setHasDraft(false) // 发布后无未发布改动
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
        setHasDraft(false) // 下线后无未发布改动（后端置 has_draft_change=0）
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

  // 手动运行（task-run-decouple）：携带编辑器当前内容（含未保存）。已发布→NORMAL 正式实例；未发布→TEST 测试实例。
  // EXECUTED 返回 instanceId → 新开日志 tab 接流；PENDING_APPROVAL 返回审批单号。
  async function handleRun() {
    setRunning(true)
    try {
      const res = await authFetch(`${API_BASE}/api/tasks/${taskId}/run`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          content,
          type,
          paramsJson: serializeParams(paramRows) || null,
          // 带上业务日期，使 ${yyyymmdd} 等日期占位符可解析（复用编辑器预览用的业务日期，默认昨天）
          bizDate: previewBizDate || null,
        }),
      })
      const j = (await res.json()) as ApiResponse<RunResult>
      if (j.code !== 0 || !j.data) {
        toast.error(j.message || t("taskEditor.toastRunFailed"))
        return
      }
      const r = j.data
      if (r.outcome === "EXECUTED" && r.resultInstanceId) {
        const tab: RunTab = {
          instanceId: r.resultInstanceId,
          taskName: name.trim() || `#${taskId}`,
          startedAt: new Date().toISOString(),
          kind: "log",
        }
        openRunTab(tab)
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
    setDirty(true)
  }
  function updateParam(idx: number, patch: Partial<ParamRow>) {
    setParamRows((rs) => rs.map((r, i) => (i === idx ? { ...r, ...patch } : r)))
    setDirty(true)
  }
  function removeParam(idx: number) {
    setParamRows((rs) => rs.filter((_, i) => i !== idx))
    setDirty(true)
  }

  if (loading) {
    return (
      <div className="flex flex-1 items-center justify-center text-sm text-muted-foreground">
        {t("common.loading")}
      </div>
    )
  }

  // 可发布：草稿态随时可发布；已发布态需有未发布改动（hasDraft）才可「再发布」。均要求已保存（非脏）。
  const canPublish = !saving && !dirty && (!published || hasDraft)

  return (
    <div className="flex h-full flex-col">
      {/* 工具栏：运行 / 保存 / 发布 + 状态 */}
      <div className="flex flex-wrap items-center gap-2 border-b border-border p-2">
        <Button size="sm" onClick={handleRun} disabled={running}>
          <HugeiconsIcon icon={RocketIcon} className="size-4" />
          {running ? t("taskEditor.running") : published ? t("taskEditor.run") : t("taskEditor.runTest")}
        </Button>
        <div className="ml-auto flex items-center gap-2">
          <Badge variant={published ? "success" : "outline"}>
            {published ? t("taskEditor.statusOnline") : t("taskEditor.statusDraft")}
          </Badge>
          <Button
            size="sm"
            variant="outline"
            onClick={handleSave}
            disabled={saving || !name.trim()}
            className={cn(dirty && "text-primary border-primary/50")}
          >
            <HugeiconsIcon icon={FloppyDiskIcon} className="size-4" />{" "}
            {dirty ? t("taskEditor.pendingSave") : t("taskEditor.saveDraft")}
          </Button>
          {/* 发布/再发布：草稿首发或已发布态发布最新改动；与「下线」同 outline 风格统一 */}
          <Button size="sm" variant="outline" onClick={handlePublish} disabled={!canPublish}>
            {t("taskEditor.publish")}
          </Button>
          {published && (
            <Button size="sm" variant="outline" onClick={handleOffline} disabled={saving}>
              {t("taskEditor.offline")}
            </Button>
          )}
        </div>
      </div>

      <div className="flex min-h-0 flex-1">
        {/* 左：代码编辑器（Monaco）+ 运行日志 Tabs */}
        <div className="flex min-w-0 flex-1 flex-col">
          <div className="min-h-0 flex-1 p-2">
            <CodeEditor
              value={content}
              onChange={(v) => {
                setContent(v)
                setDirty(true)
              }}
              language={editorLang}
              className="h-full"
            />
          </div>
          {runTabs.length > 0 && (
            <>
              {/* 拖拽分割条：样式与类目树分割线一致（hover 显高亮短条），上沿持久细线分隔 */}
              <div
                onPointerDown={onLogResizeDown}
                role="separator"
                aria-orientation="horizontal"
                aria-label={t("taskEditor.resizeLogPanel")}
                className="group/logresize relative flex h-2 shrink-0 cursor-row-resize touch-none items-center justify-center border-t border-border"
              >
                <div className="h-0.5 w-12 rounded-full bg-border/0 transition-colors group-hover/logresize:bg-border" />
              </div>
              <div className="shrink-0" style={{ height: logHeight }}>
                <RunLogsTabs
                  tabs={runTabs}
                  activeId={activeRunTab}
                  onActivate={setActiveRunTab}
                  onClose={closeRunTab}
                  onCloseOthers={closeOtherRunTabs}
                  onCloseRight={closeRunTabsRight}
                  onCloseLeft={closeRunTabsLeft}
                  onCloseAll={closeAllRunTabs}
                  locale={locale}
                />
              </div>
            </>
          )}
        </div>

        {/* 右：配置（参数行 / 预览 / 调度）—— 迁自 TaskEditPanel */}
        <DwScroll className="w-72 shrink-0 border-l border-border" innerClassName="flex flex-col gap-3 p-3">
          <div className="flex flex-col gap-1.5">
            <label className="text-xs font-medium text-muted-foreground">{t("taskEditor.name")}</label>
            <Input className="h-8 text-sm" value={name} onChange={(e) => { setName(e.target.value); setDirty(true) }} placeholder={t("taskEditor.namePlaceholder")} />
          </div>

          <div className="flex gap-3">
            <div className="flex flex-1 flex-col gap-1.5">
              <label className="text-xs font-medium text-muted-foreground">{t("taskEditor.type")}</label>
              <DropdownSelect value={type} onChange={(v) => { setType(v); setDirty(true) }} options={TYPE_OPTIONS} />
            </div>
            <div className="flex flex-1 flex-col gap-1.5">
              <label className="text-xs font-medium text-muted-foreground">{t("taskEditor.priority")}</label>
              <Input className="h-8 text-sm" type="number" min={0} max={9} value={priority} onChange={(e) => { setPriority(Number(e.target.value)); setDirty(true) }} />
            </div>
          </div>

          <div className="flex flex-col gap-1.5">
            <label className="text-xs font-medium text-muted-foreground">{t("taskEditor.description")}</label>
            <Input className="h-8 text-sm" value={description} onChange={(e) => { setDescription(e.target.value); setDirty(true) }} placeholder={t("taskEditor.descriptionPlaceholder")} />
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
                {t("taskEditor.previewBtn")}
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
              <Input className="h-8 text-sm" type="number" value={timeoutSec} onChange={(e) => { setTimeoutSec(Number(e.target.value)); setDirty(true) }} />
            </div>
            <div className="flex flex-1 flex-col gap-1.5">
              <label className="text-xs font-medium text-muted-foreground">{t("taskEditor.retryMax")}</label>
              <Input className="h-8 text-sm" type="number" min={0} value={retryMax} onChange={(e) => { setRetryMax(Number(e.target.value)); setDirty(true) }} />
            </div>
          </div>
        </DwScroll>
      </div>
    </div>
  )
}
