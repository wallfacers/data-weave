"use client"

/**
 * 057 系统设置 → 配置 → AI Agent 分区（内联表单）。
 *
 * 字段 / test / save 逻辑复用自 053 AgentConfigPanel，但**内联渲染**（非 Dialog）；
 * 调 057 全局端点（/api/settings/agent-config，无 projectId）。
 * 设计约束（DESIGN.md / CLAUDE.md Frontend Stack Gate）：
 * 语义 token、gap-* / size-*、hugeicons（非 lucide）、不手写 dark:、
 * 进度态文案不带省略号。本组件由 ConfigShell 右侧卡片提供外层 --card-spacing 留白。
 */
import { useEffect, useState } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  CheckmarkCircle01Icon,
  Cancel01Icon,
  CpuIcon,
} from "@hugeicons/core-free-icons"
import { toast } from "sonner"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Switch } from "@/components/ui/switch"
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

export function AiAgentConfigSection() {
  const t = useTranslations("lineageAgent")

  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [testing, setTesting] = useState(false)
  const [testResult, setTestResult] = useState<AgentTestResult | null>(null)
  const [error, setError] = useState("")
  const [hasExisting, setHasExisting] = useState(false)
  const [maskedKey, setMaskedKey] = useState("")

  const [protocol, setProtocol] = useState<AgentProtocol>("OPENAI")
  const [baseUrl, setBaseUrl] = useState("")
  const [model, setModel] = useState("")
  const [apiKey, setApiKey] = useState("")
  const [enabled, setEnabled] = useState(false)
  const [timeoutMs, setTimeoutMs] = useState(DEFAULT_TIMEOUT_MS)
  const [rateLimitPerMin, setRateLimitPerMin] = useState(DEFAULT_RATE_LIMIT)
  const [maxColumns, setMaxColumns] = useState(DEFAULT_MAX_COLUMNS)

  useEffect(() => {
    let cancelled = false
    const load = async () => {
      setLoading(true)
      setError("")
      try {
        const cfg = await getAgentConfig()
        if (cancelled) return
        if (cfg) {
          setHasExisting(true)
          setProtocol(cfg.protocol)
          setBaseUrl(cfg.baseUrl ?? "")
          setModel(cfg.model ?? "")
          setMaskedKey(cfg.apiKeyMasked ?? "")
          setEnabled(cfg.enabled)
          setTimeoutMs(cfg.timeoutMs)
          setRateLimitPerMin(cfg.rateLimitPerMin)
          setMaxColumns(cfg.maxColumns)
        } else {
          setHasExisting(false)
          // 无既有配置 → 回显默认值（下方空态提示引导填写）
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
  }, [])

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
      setTestResult(await testAgentConfig(buildReq()))
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
      if (cfg) {
        setHasExisting(true)
        setMaskedKey(cfg.apiKeyMasked ?? "")
      }
      setApiKey("")
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

  if (loading) {
    return <div className="py-8 text-center text-sm text-muted-foreground">{t("loading")}</div>
  }

  return (
    <div className="mx-auto w-full max-w-lg flex flex-col gap-4">
      {/* 标题 + 全局提示 */}
      <div className="flex flex-col gap-1">
        <div className="flex items-center gap-2">
          <HugeiconsIcon icon={CpuIcon} className="size-4 text-muted-foreground" />
          <h2 className="text-sm font-medium">{t("title")}</h2>
        </div>
        <p className="text-xs text-muted-foreground">{t("description")}</p>
        <p className="text-xs text-muted-foreground">{t("globalHint")}</p>
      </div>

      {/* 空态引导：尚未配置 */}
      {!hasExisting && (
        <div className="rounded-md bg-muted/60 px-3 py-2 text-xs text-muted-foreground">
          {t("emptyHint")}
        </div>
      )}

      {/* 表单 */}
      <div className="grid gap-3">
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

        {/* 数值参数三联（大范围 → Input type=number，非 Stepper） */}
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
              testResult.ok ? "bg-success/10 text-success" : "bg-destructive/10 text-destructive"
            }`}
          >
            <HugeiconsIcon
              icon={testResult.ok ? CheckmarkCircle01Icon : Cancel01Icon}
              className="size-4 shrink-0"
            />
            <span>{testResult.ok ? t("testOk", { latencyMs: testResult.latencyMs }) : t("testFailed")}</span>
            {testResult.note && <span className="text-xs opacity-70">{testResult.note}</span>}
          </div>
        )}

        {error && <p className="text-sm text-destructive">{error}</p>}
      </div>

      {/* 操作 */}
      <div className="flex items-center gap-2">
        <Button variant="outline" onClick={handleTest} disabled={testing || saving}>
          {testing ? t("form.testing") : t("form.testConnection")}
        </Button>
        <Button onClick={handleSave} disabled={saving || testing}>
          {saving ? t("form.saving") : t("form.save")}
        </Button>
      </div>
    </div>
  )
}
