"use client"

/**
 * DAG 节点详情侧面板（004-dag-node-detail-panel）。
 *
 * 在 Ops DAG 弹框右侧展示选中节点的任务配置详情：
 * 基本信息 + 代码高亮（Shiki）+ 配置参数表。
 * 面板外壳复用 {@link DetailPanelShell}，UI 子组件复用 shared/*。
 */
import { useTranslations } from "next-intl"
import { DetailPanelShell } from "@/components/workspace/detail-panel-shell"
import { CodeBlock } from "@/components/workspace/shared/code-block"
import { ParamsTable, InfoRow, taskTypeToLang } from "@/components/workspace/shared/params-table"
import { useNodeDetailStore } from "@/lib/workspace/node-detail-store"

/** 任务类型 → 人类可读标签 */
function taskTypeLabel(taskType: string, t: (key: string) => string): string {
  const m: Record<string, string> = {
    SQL: "SQL", PYTHON: "Python", SHELL: "Shell", JAVA: "Java",
    JAVASCRIPT: "JavaScript", TYPESCRIPT: "TypeScript", DATA_SYNC: "Data Sync",
  }
  return m[taskType.toUpperCase()] || taskType
}

export function NodeDetailPanel() {
  const t = useTranslations("ops")
  const selectedNode = useNodeDetailStore((s) => s.selectedNode)
  const detail = useNodeDetailStore((s) => s.detail)
  const loadState = useNodeDetailStore((s) => s.loadState)
  const errorMessage = useNodeDetailStore((s) => s.errorMessage)
  const deselectNode = useNodeDetailStore((s) => s.deselectNode)
  const retry = useNodeDetailStore((s) => s.retry)

  if (!selectedNode || loadState === "idle") return null

  const lang = detail ? taskTypeToLang(detail.taskType) : "text"
  const loading = loadState === "loading"
  const error = loadState === "error" ? (errorMessage || t("nodeDetail.fetchError")) : null
  const hasData = loadState === "loaded" || (loading && detail !== null)

  return (
    <DetailPanelShell
      title={t("nodeDetail.title")}
      onClose={deselectNode}
      loading={loading}
      error={error}
      onRetry={retry}
      hasData={hasData}
    >
      {loadState === "loaded" && detail && (
        <>
          {/* 任务已删除 */}
          {detail.deleted && (
            <div className="text-xs text-destructive font-medium py-2">
              {t("nodeDetail.taskDeleted")}
            </div>
          )}

          {/* 基本信息 */}
          <section>
            <h4 className="text-xs font-semibold text-muted-foreground mb-2 uppercase tracking-wide">
              {t("nodeDetail.taskName")}
            </h4>
            <div className="space-y-1.5">
              <InfoRow label={t("nodeDetail.taskName")} value={detail.taskName} />
              <InfoRow label={t("nodeDetail.taskType")} value={taskTypeLabel(detail.taskType, t)} />
              <InfoRow label={t("nodeDetail.versionNo")} value={`v${detail.versionNo}`} />
              {detail.publishedAt && (
                <InfoRow label={t("nodeDetail.publishedAt")} value={detail.publishedAt} />
              )}
              <InfoRow label={t("nodeDetail.timeout")} value={`${detail.timeoutSec}s`} />
              <InfoRow label={t("nodeDetail.retryMax")} value={String(detail.retryMax)} />
            </div>
          </section>

          {/* 代码 */}
          <section>
            <h4 className="text-xs font-semibold text-muted-foreground mb-2 uppercase tracking-wide">
              {t("nodeDetail.code")}
            </h4>
            {detail.content && detail.hasCode ? (
              <div className="rounded-[var(--radius-md)] border border-border overflow-hidden bg-card">
                <CodeBlock code={detail.content} lang={lang} />
              </div>
            ) : (
              <span className="text-xs text-muted-foreground">{t("nodeDetail.noCode")}</span>
            )}
          </section>

          {/* 配置参数 */}
          <section>
            <h4 className="text-xs font-semibold text-muted-foreground mb-2 uppercase tracking-wide">
              {t("nodeDetail.params")}
            </h4>
            {detail.paramsJson ? (
              <div className="rounded-md border border-border overflow-hidden p-2">
                <ParamsTable paramsJson={detail.paramsJson} />
              </div>
            ) : (
              <span className="text-xs text-muted-foreground">{t("nodeDetail.noParams")}</span>
            )}
          </section>
        </>
      )}
    </DetailPanelShell>
  )
}
