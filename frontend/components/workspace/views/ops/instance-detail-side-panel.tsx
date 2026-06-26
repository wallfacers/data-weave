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
import { DetailPanelShell } from "@/components/workspace/detail-panel-shell"
import { CodeBlock } from "@/components/workspace/shared/code-block"
import { ParamsTable, InfoRow, taskTypeToLang } from "@/components/workspace/shared/params-table"
import { authFetch, API_BASE, type ApiResponse, type ResolvedCodeView, type ResolvedConfigView } from "@/lib/types"

// ─── Main ────────────────────────────────────────────

interface InstanceDetailSidePanelProps {
  taskInstanceId: string | null
  nodeName?: string
  onClose: () => void
}

export function InstanceDetailSidePanel({
  taskInstanceId,
  nodeName,
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
