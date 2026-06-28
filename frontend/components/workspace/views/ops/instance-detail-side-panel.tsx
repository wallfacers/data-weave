"use client"

/**
 * 实例详情侧面板 —— DAG 弹窗内右侧展开，展示「实际代码」和「实际配置」。
 *
 * 风格与 NodeDetailPanel 统一：共享 DetailPanelShell + shared/* UI 子组件。
 *
 * GET /api/ops/task-instances/{id}/resolved-code
 * GET /api/ops/task-instances/{id}/resolved-config
 */

import { useEffect, useState, useCallback } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import { PlayIcon, StopIcon, CheckmarkCircle01Icon, PauseIcon, RepeatIcon } from "@hugeicons/core-free-icons"
import { toast } from "sonner"
import { DetailPanelShell } from "@/components/workspace/detail-panel-shell"
import { CodeBlock } from "@/components/workspace/shared/code-block"
import { ParamsTable, InfoRow, taskTypeToLang } from "@/components/workspace/shared/params-table"
import { Button } from "@/components/ui/button"
import { Badge } from "@/components/ui/badge"
import { authFetch, API_BASE, type ApiResponse, type ResolvedCodeView, type ResolvedConfigView } from "@/lib/types"

// ─── 任务操作可用性矩阵 (T025) ─────────────────────────

/** 根据任务节点状态 + 环境返回可用操作列表。 */
function getAvailableTaskActions(taskState: string | undefined, env: string | undefined): string[] {
  if (!taskState || env === "DEV") {
    // DEV 环境仅允许停止
    if (taskState && !["SUCCESS", "FAILED", "STOPPED", "SKIPPED"].includes(taskState)) return ["kill"]
    return []
  }
  const actions: string[] = []
  switch (taskState) {
    case "NOT_RUN":
      actions.push("pause")
      break
    case "PAUSED":
      actions.push("resume")
      break
    case "DISPATCHED":
    case "RUNNING":
      actions.push("kill")
      break
    case "FAILED":
    case "STOPPED":
    case "PREEMPTED":
      actions.push("rerun", "set-success")
      break
    case "WAITING":
      actions.push("kill")
      break
  }
  return actions
}

const ACTION_META: Record<string, { icon: typeof PlayIcon; label: string; variant: "outline" | "destructive" }> = {
  rerun: { icon: RepeatIcon, label: "rerunTask", variant: "outline" },
  "set-success": { icon: CheckmarkCircle01Icon, label: "setSuccessTask", variant: "outline" },
  kill: { icon: StopIcon, label: "killTask", variant: "destructive" },
  pause: { icon: PauseIcon, label: "pauseTask", variant: "outline" },
  resume: { icon: PlayIcon, label: "resumeTask", variant: "outline" },
}

// ─── Main ────────────────────────────────────────────

interface InstanceDetailSidePanelProps {
  taskInstanceId: string | null
  nodeName?: string
  taskState?: string
  env?: string
  onClose: () => void
}

export function InstanceDetailSidePanel({
  taskInstanceId,
  nodeName,
  taskState,
  env,
  onClose,
}: InstanceDetailSidePanelProps) {
  const t = useTranslations("ops")
  const [codeData, setCodeData] = useState<ResolvedCodeView | null>(null)
  const [configData, setConfigData] = useState<ResolvedConfigView | null>(null)
  const [loadState, setLoadState] = useState<"loading" | "loaded" | "error">("loading")
  const [errorMessage, setErrorMessage] = useState<string | null>(null)

  const load = useCallback(async () => {
    if (!taskInstanceId) return
    setLoadState("loading")
    setErrorMessage(null)
    try {
      const [codeRes, configRes] = await Promise.all([
        authFetch(`${API_BASE}/api/ops/task-instances/${taskInstanceId}/resolved-code`),
        authFetch(`${API_BASE}/api/ops/task-instances/${taskInstanceId}/resolved-config`),
      ])
      const codeJson: ApiResponse<ResolvedCodeView> = await codeRes.json()
      const configJson: ApiResponse<ResolvedConfigView> = await configRes.json()
      if (codeJson.code === 0 && codeJson.data) setCodeData(codeJson.data)
      if (configJson.code === 0 && configJson.data) setConfigData(configJson.data)
      setLoadState("loaded")
    } catch (err) {
      setErrorMessage(err instanceof Error ? err.message : "Network error")
      setLoadState("error")
    }
  }, [taskInstanceId])

  useEffect(() => {
    if (taskInstanceId) load()
  }, [taskInstanceId, load])

  if (!taskInstanceId) return null

  // ── 单任务操作 ──
  const [actionBusy, setActionBusy] = useState(false)
  const availableActions = getAvailableTaskActions(taskState, env)

  const runTaskAction = useCallback(async (action: string) => {
    if (!taskInstanceId || actionBusy) return
    setActionBusy(true)
    try {
      const endpoint = action === "set-success" ? "set-success" : action
      const res = await authFetch(`${API_BASE}/api/ops/task-instances/${taskInstanceId}/${endpoint}`, { method: "POST" })
      const j = (await res.json()) as ApiResponse<unknown>
      if (j.code === 0) {
        toast.success(t(`action${action.charAt(0).toUpperCase() + action.slice(1)}`) ?? t("actionSuccess"))
        load()
      } else {
        toast.error(t("actionFailed", { msg: (j as { msg?: string }).msg ?? "" }))
      }
    } catch {
      toast.error(t("networkError"))
    } finally {
      setActionBusy(false)
    }
  }, [taskInstanceId, actionBusy, t, load])

  const lang = codeData ? taskTypeToLang(codeData.taskType) : "text"
  const showCode = codeData && codeData.resolvedContent
  const showConfig = configData
  const isTestRun = codeData?.isOverride || configData?.isOverride
  const hasData = loadState === "loaded" || (loadState === "loading" && Boolean(codeData || configData))

  return (
    <DetailPanelShell
      title={nodeName ?? t("nodeDetail")}
      onClose={onClose}
      loading={loadState === "loading"}
      error={loadState === "error" ? (errorMessage || t("loadError")) : null}
      onRetry={load}
      hasData={hasData}
    >
      {hasData && (
        <>
          {/* 任务状态 + 操作按钮 */}
          <div className="flex items-center gap-2 flex-wrap">
            {taskState && (
              <Badge variant={taskState === "FAILED" || taskState === "STOPPED" ? "destructive" : taskState === "SUCCESS" ? "success" : taskState === "RUNNING" || taskState === "DISPATCHED" ? "success" : "outline"} className="text-xs">
                {taskState}
              </Badge>
            )}
            {availableActions.map((action) => {
              const meta = ACTION_META[action]
              if (!meta) return null
              return (
                <Button key={action} size="sm" variant={meta.variant} className="h-6 text-xs px-2" disabled={actionBusy} onClick={() => runTaskAction(action)}>
                  <HugeiconsIcon icon={meta.icon} className="size-3 mr-1" />
                  {t(meta.label as never)}
                </Button>
              )
            })}
          </div>

          {/* TEST 运行标注 */}
          {isTestRun && (
            <div className="text-xs font-medium text-amber-600 dark:text-amber-400 bg-amber-50 dark:bg-amber-950/30 px-3 py-2 rounded-md border border-amber-200 dark:border-amber-800/50">
              ⚠ {t("testRunLabel")}
            </div>
          )}

          {/* 基本信息 */}
          {configData && (
            <section>
              <h4 className="text-xs font-semibold text-muted-foreground mb-2 uppercase tracking-wide">
                {t("nodeDetail.config")}
              </h4>
              <div className="space-y-1.5">
                <InfoRow label={t("colTaskType")} value={configData.taskType} />
                <InfoRow label={t("colTimeout")} value={`${configData.timeoutSeconds}s`} />
                <InfoRow label={t("retryStrategy")} value={configData.retryStrategy} />
                <InfoRow label={t("resourceLimit")} value={configData.resourceLimit} />
                <InfoRow label={t("colVersion")} value={`v${configData.taskVersionNo}`} />
              </div>
            </section>
          )}

          {/* 未解析参数提示 */}
          {codeData && codeData.unresolvedPlaceholders.length > 0 && (
            <div className="text-xs text-amber-600 dark:text-amber-400 bg-amber-50 dark:bg-amber-950/20 px-3 py-2 rounded-md border border-amber-200 dark:border-amber-800/50">
              ⚠ {t("unresolvedParams")}: {codeData.unresolvedPlaceholders.join(", ")}
            </div>
          )}

          {/* 实际代码 */}
          {showCode && (
            <section>
              <h4 className="text-xs font-semibold text-muted-foreground mb-2 uppercase tracking-wide">
                {t("tabActualCode")}
              </h4>
              <div className="rounded-[var(--radius-md)] border border-border overflow-hidden bg-card">
                <CodeBlock code={codeData.resolvedContent} lang={lang} />
              </div>
              {/* 原始模板（可折叠） */}
              {codeData.rawContent !== codeData.resolvedContent && (
                <details className="mt-2 text-xs">
                  <summary className="cursor-pointer text-muted-foreground hover:text-foreground">
                    {t("showRawContent")}
                  </summary>
                  <div className="mt-2 rounded-[var(--radius-md)] border border-border overflow-hidden bg-muted/50">
                    <CodeBlock code={codeData.rawContent} lang={lang} />
                  </div>
                </details>
              )}
            </section>
          )}

          {/* 实际配置参数 */}
          {showConfig && configData.resolvedParamsJson && (
            <section>
              <h4 className="text-xs font-semibold text-muted-foreground mb-2 uppercase tracking-wide">
                {t("tabActualConfig")}
              </h4>
              <div className="rounded-md border border-border overflow-hidden p-3">
                <ParamsTable paramsJson={configData.resolvedParamsJson} />
              </div>
              {/* TEST 覆盖 → 展示原始参数对比 */}
              {configData.isOverride && configData.originalParamsJson && (
                <details className="mt-2 text-xs">
                  <summary className="cursor-pointer text-muted-foreground hover:text-foreground">
                    {t("showOriginalParams")}
                  </summary>
                  <div className="mt-2 rounded-md border border-border overflow-hidden p-3 bg-muted/50">
                    <ParamsTable paramsJson={configData.originalParamsJson} />
                  </div>
                </details>
              )}
            </section>
          )}
        </>
      )}
    </DetailPanelShell>
  )
}
