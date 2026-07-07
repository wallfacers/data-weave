"use client"

/**
 * 053 T015：血缘 AI Agent 配置面板。
 *
 * 自包含组件：触发按钮 + Dialog 表单。挂在血缘探索器工具栏（lineage-toolbar）。
 * - 加载调 getAgentConfig 回显（apiKey 字段恒空，仅显示 apiKeyMasked 提示）
 * - 测试调 testAgentConfig，成功/失败用语义色 + hugeicons 反馈
 * - 保存调 putAgentConfig（apiKey 留空 = 不改，对齐 PATCH null vs 缺失语义）
 *
 * 设计约束（DESIGN.md / CLAUDE.md Frontend Stack Gate）：
 * 语义 token、gap-* / size-*、hugeicons（非 lucide）、不手写 dark:、
 * 进度态文案不带省略号。
 */
import { useEffect, useState } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  Settings02Icon,
  CheckmarkCircle01Icon,
  Cancel01Icon,
} from "@hugeicons/core-free-icons"
import { toast } from "sonner"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Switch } from "@/components/ui/switch"
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter,
} from "@/components/ui/dialog"
import { DropdownSelect } from "@/components/ui/select"
import {
  getAgentConfig,
  putAgentConfig,
  testAgentConfig,
  type AgentConfigRequest,
  type AgentProtocol,
  type AgentTestResult,
} from "@/lib/lineage-api"

/** 默认值（无既有配置时回显，对齐后端 defaults）。 */
const DEFAULT_TIMEOUT_MS = 30000
const DEFAULT_RATE_LIMIT = 60
const DEFAULT_MAX_COLUMNS = 2000

export function AgentConfigPanel() {
  const t = useTranslations("lineageAgent")

  const [open, setOpen] = useState(false)
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [testing, setTesting] = useState(false)
  const [testResult, setTestResult] = useState<AgentTestResult | null>(null)
  const [error, setError] = useState("")
  const [maskedKey, setMaskedKey] = useState("")

  const [protocol, setProtocol] = useState<AgentProtocol>("OPENAI")
  const [baseUrl, setBaseUrl] = useState("")
  const [model, setModel] = useState("")
  const [apiKey, setApiKey] = useState("")
  const [enabled, setEnabled] = useState(false)
  const [timeoutMs, setTimeoutMs] = useState(DEFAULT_TIMEOUT_MS)
  const [rateLimitPerMin, setRateLimitPerMin] = useState(DEFAULT_RATE_LIMIT)
  const [maxColumns, setMaxColumns] = useState(DEFAULT_MAX_COLUMNS)

  // 打开时拉取当前项目配置回显
  useEffect(() => {
    if (!open) return
    let cancelled = false
    const load = async () => {
      setLoading(true)
      setError("")
      try {
        const cfg = await getAgentConfig()
        if (cancelled) return
        if (cfg) {
          setProtocol(cfg.protocol)
          setBaseUrl(cfg.baseUrl ?? "")
          setModel(cfg.model ?? "")
          setMaskedKey(cfg.apiKeyMasked ?? "")
          setEnabled(cfg.enabled)
          setTimeoutMs(cfg.timeoutMs)
          setRateLimitPerMin(cfg.rateLimitPerMin)
          setMaxColumns(cfg.maxColumns)
        } else {
          setProtocol("OPENAI")
          setBaseUrl("")
          setModel("")
          setMaskedKey("")
          setEnabled(false)
          setTimeoutMs(DEFAULT_TIMEOUT_MS)
          setRateLimitPerMin(DEFAULT_RATE_LIMIT)
          setMaxColumns(DEFAULT_MAX_COLUMNS)
        }
        setApiKey("")
        setTestResult(null)
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : "Load failed")
      } finally {
        if (!cancelled) setLoading(false)
      }
    }
    void load()
    return () => {
      cancelled = true
    }
  }, [open])

  const buildReq = (): AgentConfigRequest => ({
    protocol,
    baseUrl: baseUrl.trim(),
    model: model.trim(),
    // apiKey 留空 = 不改（后端保留原密文）
    apiKey: apiKey.trim() || undefined,
    enabled,
    timeoutMs,
    rateLimitPerMin,
    maxColumns,
  })

  const handleTest = async () => {
    setTesting(true)
    setTestResult(null)
    setError("")
    try {
      const r = await testAgentConfig(buildReq())
      setTestResult(r)
    } catch (e) {
      setTestResult({
        ok: false,
        latencyMs: 0,
        note: e instanceof Error ? e.message : t("testFailed"),
      })
    } finally {
      setTesting(false)
    }
  }

  const handleSave = async () => {
    setSaving(true)
    setError("")
    try {
      await putAgentConfig(buildReq())
      toast.success(t("savedToast"))
      // 保存后重新拉取以刷新脱敏密钥回显
      const cfg = await getAgentConfig()
      if (cfg) setMaskedKey(cfg.apiKeyMasked ?? "")
      setApiKey("")
      setOpen(false)
    } catch (e) {
      setError(e instanceof Error ? e.message : "Save failed")
    } finally {
      setSaving(false)
    }
  }

  const protocolOptions = [
    { value: "ANTHROPIC", label: t("form.protocolAnthropic") },
    { value: "OPENAI", label: t("form.protocolOpenai") },
  ]

  return (
    <>
      {/* 触发按钮 —— 与工具栏其它操作按钮同款文字按钮样式 */}
      <button
        type="button"
        onClick={() => setOpen(true)}
        className="inline-flex items-center gap-1 rounded px-2 py-1 text-xs text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
      >
        <HugeiconsIcon icon={Settings02Icon} className="size-3.5" />
        {t("openBtn")}
      </button>

      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="max-w-lg">
          <DialogHeader>
            <DialogTitle>{t("title")}</DialogTitle>
          </DialogHeader>

          {loading ? (
            <div className="py-8 text-center text-sm text-muted-foreground">{t("loading")}</div>
          ) : (
            <div className="grid gap-3 py-2">
              {/* 协议 */}
              <div className="grid gap-1">
                <label className="text-sm font-medium">{t("form.protocol")}</label>
                <DropdownSelect
                  value={protocol}
                  onChange={(v) => {
                    setProtocol(v as AgentProtocol)
                    setTestResult(null)
                  }}
                  options={protocolOptions}
                  triggerClassName="h-9"
                  disableClear
                />
              </div>

              {/* Base URL */}
              <div className="grid gap-1">
                <label className="text-sm font-medium">{t("form.baseUrl")}</label>
                <Input
                  value={baseUrl}
                  onChange={(e) => {
                    setBaseUrl(e.target.value)
                    setTestResult(null)
                  }}
                  placeholder={t("form.baseUrlPh")}
                />
              </div>

              {/* 模型 */}
              <div className="grid gap-1">
                <label className="text-sm font-medium">{t("form.model")}</label>
                <Input
                  value={model}
                  onChange={(e) => {
                    setModel(e.target.value)
                    setTestResult(null)
                  }}
                  placeholder={t("form.modelPh")}
                />
              </div>

              {/* API Key（留空=不改；已配置时下方提示脱敏值） */}
              <div className="grid gap-1">
                <label className="text-sm font-medium">{t("form.apiKey")}</label>
                <Input
                  type="password"
                  value={apiKey}
                  onChange={(e) => {
                    setApiKey(e.target.value)
                    setTestResult(null)
                  }}
                  placeholder={t("form.apiKeyHint")}
                />
                {maskedKey && (
                  <p className="text-xs text-muted-foreground">
                    {t("form.apiKeyMasked", { masked: maskedKey })}
                  </p>
                )}
              </div>

              {/* 数值参数三联 */}
              <div className="grid grid-cols-3 gap-3">
                <div className="grid gap-1">
                  <label className="text-sm font-medium">{t("form.timeoutMs")}</label>
                  <Input
                    type="number"
                    value={timeoutMs}
                    onChange={(e) => setTimeoutMs(Number(e.target.value) || 0)}
                  />
                </div>
                <div className="grid gap-1">
                  <label className="text-sm font-medium">{t("form.rateLimitPerMin")}</label>
                  <Input
                    type="number"
                    value={rateLimitPerMin}
                    onChange={(e) => setRateLimitPerMin(Number(e.target.value) || 0)}
                  />
                </div>
                <div className="grid gap-1">
                  <label className="text-sm font-medium">{t("form.maxColumns")}</label>
                  <Input
                    type="number"
                    value={maxColumns}
                    onChange={(e) => setMaxColumns(Number(e.target.value) || 0)}
                  />
                </div>
              </div>

              {/* 启用开关 */}
              <div className="flex items-center justify-between rounded-md border px-3 py-2">
                <label className="text-sm font-medium">{t("form.enabled")}</label>
                <Switch checked={enabled} onCheckedChange={setEnabled} />
              </div>

              {/* 测试结果反馈 */}
              {testResult && (
                <div
                  className={`flex items-center gap-2 rounded px-3 py-2 text-sm ${
                    testResult.ok
                      ? "bg-success/10 text-success"
                      : "bg-destructive/10 text-destructive"
                  }`}
                >
                  <HugeiconsIcon
                    icon={testResult.ok ? CheckmarkCircle01Icon : Cancel01Icon}
                    className="size-4 shrink-0"
                  />
                  <span>
                    {testResult.ok
                      ? t("testOk", { latencyMs: testResult.latencyMs })
                      : t("testFailed")}
                  </span>
                  {testResult.note && (
                    <span className="text-xs opacity-70">{testResult.note}</span>
                  )}
                </div>
              )}

              {error && <p className="text-sm text-destructive">{error}</p>}
            </div>
          )}

          <DialogFooter className="gap-2">
            <Button variant="outline" onClick={() => setOpen(false)}>
              {t("cancel")}
            </Button>
            <Button variant="outline" onClick={handleTest} disabled={testing || loading}>
              {testing ? t("form.testing") : t("form.testConnection")}
            </Button>
            <Button onClick={handleSave} disabled={saving || loading}>
              {saving ? t("form.saving") : t("form.save")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  )
}
