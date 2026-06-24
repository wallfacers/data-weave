/**
 * 通用 Agent 举手台（proactive-agent-discovery §D1/Group 9）。
 *
 * 从只渲染 TaskDiagnosis 升级为渲染通用 Finding[]（store.findings，由 GET /api/findings
 * 拉取、agent.finding 事件实时刷新）。一键修复 POST /api/findings/{id}/apply 按 outcome
 * 分流：executed（成功移除）/ PENDING_APPROVAL（卡片内联同意·拒绝，提交闸门决策）/ rejected。
 */
"use client"

import { useState } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  Alert01Icon,
  Bug01Icon,
  CheckmarkCircle01Icon,
  Loading03Icon,
} from "@hugeicons/core-free-icons"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { DwScroll } from "@/components/ui/dw-scroll"
import { useChatStore } from "@/lib/chat/store"
import type { ApplyResult, Finding } from "@/lib/chat/types"

const SEVERITY_TONE: Record<string, string> = {
  CRITICAL: "bg-destructive/10 text-destructive",
  WARN: "bg-warning/15 text-warning",
  INFO: "bg-info/10 text-info",
}

export function FindingsRail() {
  const t = useTranslations("findings")
  const findings = useChatStore((s) => s.findings)
  const loading = useChatStore((s) => s.findingsLoading)

  return (
    <aside className="flex w-[360px] shrink-0 flex-col">
      <div className="flex items-center gap-2 border-b px-5 py-3">
        <HugeiconsIcon icon={Bug01Icon} className="size-4 text-primary" />
        <h2 className="text-sm font-semibold tracking-tight">{t("railTitle")}</h2>
        {findings.length > 0 && (
          <span className="ml-auto rounded-full bg-destructive/10 px-2 py-0.5 text-xs font-medium text-destructive tabular-nums">
            {findings.length}
          </span>
        )}
      </div>
      {findings.length === 0 ? (
        <div className="flex flex-1 items-center justify-center p-8 text-center">
          <p className="text-sm text-muted-foreground">
            {loading ? t("loading") : t("railEmpty")}
          </p>
        </div>
      ) : (
        <DwScroll className="flex-1" innerClassName="flex flex-col gap-3 p-4">
          {findings.map((f) => (
            <FindingCard key={f.id} finding={f} />
          ))}
        </DwScroll>
      )}
    </aside>
  )
}

function FindingCard({ finding }: { finding: Finding }) {
  const t = useTranslations("findings")
  const apply = useChatStore((s) => s.applyFinding)
  const decide = useChatStore((s) => s.decidePermission)
  const [pending, setPending] = useState<string | null>(null)
  const [result, setResult] = useState<ApplyResult | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [approvalId, setApprovalId] = useState<string | number | null>(null)
  const [decided, setDecided] = useState<"allowed" | "denied" | null>(null)

  async function onApply(actionKey: string) {
    setPending(actionKey)
    setError(null)
    setResult(null)
    try {
      const r = await apply(finding.id, actionKey)
      setResult(r)
      if (r.outcome === "PENDING_APPROVAL" && r.approvalId != null) {
        setApprovalId(r.approvalId)
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : t("execFailed"))
    } finally {
      setPending(null)
    }
  }

  async function onDecide(action: "approve" | "reject") {
    if (approvalId == null) return
    setPending(action)
    try {
      await decide(String(approvalId), action)
      setDecided(action === "approve" ? "allowed" : "denied")
    } finally {
      setPending(null)
    }
  }

  const tone = SEVERITY_TONE[finding.severity] ?? SEVERITY_TONE.WARN

  return (
    <div className="rounded-[var(--radius-lg)] border bg-card p-4 shadow-sm">
      <div className="flex items-start gap-3">
        <div className={`flex size-9 shrink-0 items-center justify-center rounded-lg ${tone}`}>
          <HugeiconsIcon
            icon={finding.severity === "CRITICAL" ? Alert01Icon : Bug01Icon}
            className="size-4"
          />
        </div>
        <div className="flex min-w-0 flex-col gap-1">
          <span className="text-sm font-semibold">{finding.title}</span>
          <div className="flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
            <Badge className={tone}>{finding.severity}</Badge>
            <span>{t("source")}: {finding.source}</span>
            {finding.targetId && (
              <>
                <span>·</span>
                <span className="font-mono">{finding.targetId}</span>
              </>
            )}
          </div>
        </div>
      </div>

      <p className="mt-2.5 text-sm leading-relaxed text-foreground">{finding.rootCause}</p>

      {Object.keys(finding.evidence).length > 0 && (
        <div className="mt-2.5 grid gap-1.5 rounded-[var(--radius-md)] bg-muted/50 p-3 font-sans text-xs">
          {Object.entries(finding.evidence).map(([k, v]) => (
            <div key={k} className="flex gap-2">
              <span className="shrink-0 text-muted-foreground">{k}:</span>
              <span className="break-all text-foreground">
                {typeof v === "object" ? JSON.stringify(v) : String(v)}
              </span>
            </div>
          ))}
        </div>
      )}

      {finding.actions.length > 0 && !result && (
        <div className="mt-3 flex flex-wrap gap-2">
          {finding.actions.map((a) => (
            <Button
              key={a.key}
              variant="outline"
              size="sm"
              disabled={pending !== null}
              onClick={() => onApply(a.key)}
            >
              {pending === a.key && (
                <HugeiconsIcon
                  icon={Loading03Icon}
                  className="animate-spin"
                  data-icon="inline-start"
                />
              )}
              {a.label}
            </Button>
          ))}
        </div>
      )}

      {result && (
        <div
          className={`mt-3 flex items-center gap-2 rounded-[var(--radius-md)] px-3 py-2 text-xs ${
            result.outcome === "executed"
              ? "bg-success/10 text-success"
              : result.outcome === "PENDING_APPROVAL"
                ? "bg-warning/10 text-warning"
                : "bg-destructive/10 text-destructive"
          }`}
        >
          <HugeiconsIcon
            icon={result.outcome === "executed" ? CheckmarkCircle01Icon : Alert01Icon}
            className="size-4 shrink-0"
          />
          <span>{result.message}</span>
        </div>
      )}

      {/* PENDING_APPROVAL 内联审批 */}
      {result?.outcome === "PENDING_APPROVAL" && approvalId != null && !decided && (
        <div className="mt-2 flex gap-2">
          <Button size="sm" disabled={pending !== null} onClick={() => onDecide("approve")}>
            {t("approve")}
          </Button>
          <Button
            size="sm"
            variant="ghost"
            disabled={pending !== null}
            onClick={() => onDecide("reject")}
          >
            {t("reject")}
          </Button>
        </div>
      )}

      {decided && (
        <div className="mt-2 text-xs text-muted-foreground">
          {t(decided === "allowed" ? "approved" : "rejected")}
        </div>
      )}

      {error && <div className="mt-2 text-xs text-destructive">{error}</div>}
    </div>
  )
}
