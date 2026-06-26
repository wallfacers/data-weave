"use client"

/**
 * DAG 节点详情侧面板（004-dag-node-detail-panel）。
 *
 * 在 Ops DAG 弹框右侧展示选中节点的任务配置详情：
 * 基本信息 + 代码高亮（Shiki）+ 配置参数表。
 * 面板由 {@link DagViewerDialog} 集成，状态由 {@link useNodeDetailStore} 管理。
 */
import { useEffect, useState } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import { Cancel01Icon, RefreshIcon } from "@hugeicons/core-free-icons"
import { Button } from "@/components/ui/button"
import { DwScroll } from "@/components/ui/dw-scroll"
import { highlightCode } from "@/components/chat/highlighter"
import { useNodeDetailStore, type PanelLoadState } from "@/lib/workspace/node-detail-store"

// ─── Helpers ────────────────────────────────────────

/** 任务类型 → Shiki lang 映射。未知类型返回 "text"（无高亮）。 */
function taskTypeToLang(taskType: string): string {
  const m: Record<string, string> = {
    SQL: "sql",
    PYTHON: "python",
    SHELL: "bash",
    JAVA: "java",
    JAVASCRIPT: "javascript",
    TYPESCRIPT: "typescript",
    JSON: "json",
    YAML: "yaml",
    XML: "html",
    BASH: "bash",
  }
  return m[taskType.toUpperCase()] || "text"
}

/** 任务类型 → 人类可读标签 */
function taskTypeLabel(taskType: string, t: (key: string) => string): string {
  const m: Record<string, string> = {
    SQL: "SQL",
    PYTHON: "Python",
    SHELL: "Shell",
    JAVA: "Java",
    JAVASCRIPT: "JavaScript",
    TYPESCRIPT: "TypeScript",
    DATA_SYNC: "Data Sync",
  }
  return m[taskType.toUpperCase()] || taskType
}

// ─── Sub-components ─────────────────────────────────

/** 加载中状态 */
function LoadingState() {
  return (
    <div className="flex flex-col items-center justify-center gap-2 py-12 text-muted-foreground">
      <HugeiconsIcon icon={RefreshIcon} className="size-5 animate-spin" />
      <span className="text-xs">Loading…</span>
    </div>
  )
}

/** 错误状态 + 重试按钮 */
function ErrorState({ message, onRetry }: { message: string; onRetry: () => void }) {
  const t = useTranslations("ops")
  return (
    <div className="flex flex-col items-center justify-center gap-3 py-12 text-muted-foreground">
      <span className="text-xs text-destructive">{message}</span>
      <Button variant="outline" size="sm" onClick={onRetry}>
        {t("nodeDetail.retry")}
      </Button>
    </div>
  )
}

/** 代码块：Shiki 高亮，只读 */
function CodeBlock({ code, lang }: { code: string; lang: string }) {
  const [html, setHtml] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    highlightCode(lang, code).then((h) => {
      if (!cancelled) setHtml(h)
    })
    return () => { cancelled = true }
  }, [code, lang])

  if (html === null) {
    return (
      <div className="text-xs text-muted-foreground p-2">
        <HugeiconsIcon icon={RefreshIcon} className="size-4 animate-spin inline mr-1" />
        Highlighting…
      </div>
    )
  }

  return (
    <div
      className="text-xs [&_pre]:!bg-transparent [&_pre]:!p-2 [&_pre]:!m-0 overflow-x-auto"
      dangerouslySetInnerHTML={{ __html: html }}
    />
  )
}

/** 配置参数键值对表格 */
function ParamsTable({ paramsJson }: { paramsJson: string }) {
  const t = useTranslations("ops")
  let params: Record<string, string> = {}
  try {
    params = JSON.parse(paramsJson)
  } catch {
    // invalid JSON → show raw
    return <pre className="text-xs text-muted-foreground p-2 whitespace-pre-wrap">{paramsJson}</pre>
  }
  const entries = Object.entries(params)
  if (entries.length === 0) {
    return <span className="text-xs text-muted-foreground">{t("nodeDetail.noParams")}</span>
  }
  return (
    <table className="w-full text-xs">
      <tbody>
        {entries.map(([k, v]) => (
          <tr key={k} className="border-b border-border/50">
            <td className="py-1 pr-2 font-medium text-foreground/70 whitespace-nowrap">{k}</td>
            <td className="py-1 text-muted-foreground break-all">{String(v)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

// ─── Main component ──────────────────────────────────

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

  return (
    <div className="flex flex-col h-full min-w-[280px] border-l border-border">
      {/* Header */}
      <div className="shrink-0 flex items-center justify-between px-4 py-3 border-b border-border">
        <span className="text-sm font-medium truncate">{t("nodeDetail.title")}</span>
        <Button
          variant="ghost"
          size="icon-sm"
          aria-label={t("nodeDetail.close")}
          onClick={deselectNode}
        >
          <HugeiconsIcon icon={Cancel01Icon} className="size-4" />
        </Button>
      </div>

      {/* Body */}
      <DwScroll direction="vertical" className="flex-1 min-h-0" innerClassName="flex flex-col gap-4 p-4">
        {loadState === "loading" && <LoadingState />}

        {loadState === "error" && (
          <ErrorState message={errorMessage || t("nodeDetail.fetchError")} onRetry={retry} />
        )}

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
                <div className="rounded-md border border-border overflow-hidden">
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
      </DwScroll>
    </div>
  )
}

/** 单行信息展示 */
function InfoRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-baseline gap-2">
      <span className="text-xs text-muted-foreground shrink-0">{label}</span>
      <span className="text-xs text-foreground truncate">{value}</span>
    </div>
  )
}
