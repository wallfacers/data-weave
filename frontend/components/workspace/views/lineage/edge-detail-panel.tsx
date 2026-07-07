"use client"

/**
 * 血缘边纠正内容组件（052 迁入 DetailPanelShell：去自有外壳，仅保留 body 逻辑）。
 *
 * 保留 041 全部能力：来源/置信度/人工裁决态 + 未解析提示 + 确认/剔除/撤销操作。
 * 门禁不变：project:manage 权限才显示操作区；写仍走 /corrections 闸门（FR-026）。
 *
 * 设计约束（DESIGN.md）：语义 token、无分割线、hugeicons、不手写 dark:。
 */
import { useCallback, useEffect, useState } from "react"
import { useTranslations } from "next-intl"
import { toast } from "sonner"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  ArrowRight02Icon,
  CheckmarkBadge01Icon,
  Tick01Icon,
  Delete02Icon,
  ArrowTurnBackwardIcon,
} from "@hugeicons/core-free-icons"
import type {
  FlowEdgeView,
  GraphNodeView,
  UnresolvedHintView,
  CorrectionView,
} from "@/lib/lineage-api"
import {
  fetchTaskHints,
  fetchTaskCorrections,
  postCorrection,
} from "@/lib/lineage-api"
import { useProjectPermissions } from "@/lib/project-permissions"

export interface EdgeCorrectionsContentProps {
  edge: FlowEdgeView
  nodes: GraphNodeView[]
  /** 裁决成功后回调（父组件刷新子图）。 */
  onChanged: () => void
}

type ActionKind = "CONFIRM" | "REMOVE" | "REVOKE"

export function EdgeCorrectionsContent({ edge, nodes, onChanged }: EdgeCorrectionsContentProps) {
  const t = useTranslations("lineageView")
  const { can } = useProjectPermissions()
  const canManage = can("project:manage")

  const [hints, setHints] = useState<UnresolvedHintView[]>([])
  const [corrections, setCorrections] = useState<CorrectionView[]>([])
  const [acting, setActing] = useState<ActionKind | null>(null)

  const fromName = nodes.find((n) => n.id === edge.from)?.name ?? edge.from
  const toName = nodes.find((n) => n.id === edge.to)?.name ?? edge.to

  // 来源翻译：脚本三通道各自文案，其余归入 SQL 解析
  const sourceKey =
    edge.source === "SCRIPT_SQL"
      ? "sourceScriptSql"
      : edge.source === "SCRIPT_INFERRED"
        ? "sourceScriptInferred"
        : edge.source === "SCRIPT_MODEL"
          ? "sourceScriptModel"
          : "sourceSqlParsed"

  const isInferred =
    edge.source === "SCRIPT_INFERRED" || edge.source === "SCRIPT_MODEL"

  const loadCorrections = useCallback(async () => {
    if (edge.taskDefId == null) {
      setCorrections([])
      return
    }
    try {
      const res = await fetchTaskCorrections(edge.taskDefId)
      setCorrections(res.data ?? [])
    } catch {
      setCorrections([])
    }
  }, [edge.taskDefId])

  useEffect(() => {
    let cancelled = false
    setHints([])
    if (edge.taskDefId != null) {
      fetchTaskHints(edge.taskDefId)
        .then((res) => {
          if (!cancelled) setHints(res.data ?? [])
        })
        .catch(() => {
          if (!cancelled) setHints([])
        })
    }
    void loadCorrections()
    return () => {
      cancelled = true
    }
  }, [edge.taskDefId, loadCorrections])

  // 当前边写侧语义键（WRITE|edge.to）是否有生效裁决
  const activeCorrection = corrections.find(
    (c) => c.direction === "WRITE" && c.tableKey === edge.to,
  )

  const runAction = useCallback(
    async (action: ActionKind) => {
      if (edge.taskDefId == null || acting) return
      setActing(action)
      try {
        const res = await postCorrection({
          action,
          taskDefId: edge.taskDefId,
          direction: "WRITE",
          tableKey: edge.to,
        })
        toast.success(res.data?.message ?? t("correctionDone"))
        await loadCorrections()
        onChanged()
      } catch {
        toast.error(t("correctionFailed"))
      } finally {
        setActing(null)
      }
    },
    [edge.taskDefId, edge.to, acting, loadCorrections, onChanged, t],
  )

  return (
    <div className="flex flex-col gap-3">
      {/* from → to */}
      <div className="flex items-center gap-2 rounded-md bg-muted/50 px-3 py-2 text-sm">
        <span className="truncate font-medium">{fromName}</span>
        <HugeiconsIcon icon={ArrowRight02Icon} className="size-4 text-muted-foreground shrink-0" />
        <span className="truncate font-medium">{toName}</span>
      </div>

      {/* 已确认徽章 */}
      {edge.humanState === "CONFIRMED" && (
        <div className="flex items-center gap-1.5 text-xs text-success">
          <HugeiconsIcon icon={CheckmarkBadge01Icon} className="size-3.5" />
          {t("edgeConfirmed")}
        </div>
      )}

      {/* 属性列表 */}
      <div className="space-y-1.5 text-xs">
        <div className="flex items-center gap-2">
          <span className="w-20 shrink-0 text-muted-foreground">{t("edgeSource")}</span>
          <span>{t(sourceKey)}</span>
        </div>
        {edge.confidence && (
          <div className="flex items-center gap-2">
            <span className="w-20 shrink-0 text-muted-foreground">{t("edgeConfidence")}</span>
            <span>{edge.confidence}</span>
          </div>
        )}
        {edge.modelVersion && (
          <div className="flex items-center gap-2">
            <span className="w-20 shrink-0 text-muted-foreground">{t("edgeModelVersion")}</span>
            <span className="truncate">{edge.modelVersion}</span>
          </div>
        )}
      </div>

      {/* 未解析提示 */}
      {edge.taskDefId != null && (
        <div>
          <div className="text-xs font-medium text-muted-foreground pb-1.5">{t("hintsTitle")}</div>
          {hints.length === 0 ? (
            <div className="text-xs text-muted-foreground py-1">{t("hintsEmpty")}</div>
          ) : (
            <div className="space-y-1">
              {hints.map((h) => (
                <div key={h.id} className="flex items-start gap-2 rounded-md bg-muted/50 px-3 py-2 text-xs">
                  <span className="text-[10px] text-muted-foreground rounded bg-muted px-1.5 py-0.5 shrink-0">
                    {h.kind}
                  </span>
                  <span className="break-all">{h.scriptHint}</span>
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {/* 操作区（仅项目管理权限） */}
      {canManage && edge.taskDefId != null && (
        <div className="flex items-center gap-2 pt-1">
          {isInferred && edge.humanState !== "CONFIRMED" && (
            <button
              className="flex items-center gap-1 rounded-md bg-primary px-2.5 py-1.5 text-xs text-primary-foreground transition-colors hover:bg-primary/90 disabled:opacity-50"
              disabled={acting != null}
              onClick={() => runAction("CONFIRM")}
            >
              <HugeiconsIcon icon={Tick01Icon} className="size-3.5" />
              {t("actionConfirm")}
            </button>
          )}
          <button
            className="flex items-center gap-1 rounded-md bg-destructive/10 px-2.5 py-1.5 text-xs text-destructive transition-colors hover:bg-destructive/20 disabled:opacity-50"
            disabled={acting != null}
            onClick={() => runAction("REMOVE")}
          >
            <HugeiconsIcon icon={Delete02Icon} className="size-3.5" />
            {t("actionRemove")}
          </button>
          {activeCorrection && (
            <button
              className="flex items-center gap-1 rounded-md bg-muted px-2.5 py-1.5 text-xs text-muted-foreground transition-colors hover:bg-muted/70 disabled:opacity-50"
              disabled={acting != null}
              onClick={() => runAction("REVOKE")}
            >
              <HugeiconsIcon icon={ArrowTurnBackwardIcon} className="size-3.5" />
              {t("actionRevoke")}
            </button>
          )}
        </div>
      )}
    </div>
  )
}
