"use client"

/**
 * 041 血缘边详情面板：来源/置信度/人工裁决态 + 未解析提示 + 确认/剔除/撤销操作。
 *
 * 语义键简化（与后端 corrections 契约对齐）：表级边对应 READ|from 与 WRITE|to 两个键，
 * 本面板的确认/剔除统一作用于写侧键（direction=WRITE, tableKey=edge.to）；
 * 列级边同理，tableKey 直接用目标节点 id，columnKey 不传。
 *
 * 设计约束（DESIGN.md）：语义 token，无分割线，hugeicons，不手写 dark:，加载中不用省略号。
 */
import { useCallback, useEffect, useState } from "react"
import { useTranslations } from "next-intl"
import { toast } from "sonner"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  ArrowRight02Icon,
  Cancel01Icon,
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

interface Props {
  edge: FlowEdgeView
  nodes: GraphNodeView[]
  onClose: () => void
  /** 裁决成功后回调（父组件刷新子图）。 */
  onChanged: () => void
}

type ActionKind = "CONFIRM" | "REMOVE" | "REVOKE"

export function EdgeDetailPanel({ edge, nodes, onClose, onChanged }: Props) {
  const t = useTranslations("lineageView")
  const { can } = useProjectPermissions()
  const canManage = can("project:manage")

  const [hints, setHints] = useState<UnresolvedHintView[]>([])
  const [corrections, setCorrections] = useState<CorrectionView[]>([])
  const [acting, setActing] = useState<ActionKind | null>(null)

  const fromName = nodes.find((n) => n.id === edge.from)?.name ?? edge.from
  const toName = nodes.find((n) => n.id === edge.to)?.name ?? edge.to

  // 来源翻译：脚本三通道各自文案，其余（AGENT/SQL_PARSED/FORM/缺省）归入 SQL 解析
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
    (c) => c.direction === "WRITE" && c.tableKey === edge.to
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
    [edge.taskDefId, edge.to, acting, loadCorrections, onChanged, t]
  )

  return (
    <div className="flex flex-col h-full bg-card">
      {/* 标题栏 */}
      <div className="flex items-center gap-2 px-4 py-2 shrink-0">
        <span className="text-sm font-medium">{t("edgeDetailTitle")}</span>
        {edge.humanState === "CONFIRMED" && (
          <span className="flex items-center gap-1 text-xs text-primary bg-primary/10 px-1.5 py-0.5 rounded">
            <HugeiconsIcon icon={CheckmarkBadge01Icon} className="size-3" />
            {t("edgeConfirmed")}
          </span>
        )}
        <button
          className="ml-auto text-muted-foreground hover:text-foreground transition-colors"
          onClick={onClose}
          aria-label={t("close")}
        >
          <HugeiconsIcon icon={Cancel01Icon} className="size-4" />
        </button>
      </div>

      {/* from → to */}
      <div className="px-4 py-2 shrink-0">
        <div className="flex items-center gap-2 px-3 py-2 rounded-md bg-muted/50 text-sm">
          <span className="truncate font-medium">{fromName}</span>
          <HugeiconsIcon
            icon={ArrowRight02Icon}
            className="size-4 text-muted-foreground shrink-0"
          />
          <span className="truncate font-medium">{toName}</span>
        </div>
      </div>

      {/* 信息列表 */}
      <div className="px-4 py-2 shrink-0 space-y-1.5 text-sm">
        <div className="flex items-center gap-2">
          <span className="text-muted-foreground w-20 shrink-0">{t("edgeSource")}</span>
          <span>{t(sourceKey)}</span>
        </div>
        {edge.confidence && (
          <div className="flex items-center gap-2">
            <span className="text-muted-foreground w-20 shrink-0">{t("edgeConfidence")}</span>
            <span>{edge.confidence}</span>
          </div>
        )}
        {edge.modelVersion && (
          <div className="flex items-center gap-2">
            <span className="text-muted-foreground w-20 shrink-0">{t("edgeModelVersion")}</span>
            <span className="truncate">{edge.modelVersion}</span>
          </div>
        )}
      </div>

      {/* 未解析提示 */}
      {edge.taskDefId != null && (
        <div className="flex-1 min-h-0 overflow-auto px-4 py-2">
          <div className="text-xs font-medium text-muted-foreground pb-1.5">
            {t("hintsTitle")}
          </div>
          {hints.length === 0 ? (
            <div className="text-xs text-muted-foreground py-1">{t("hintsEmpty")}</div>
          ) : (
            <div className="space-y-1">
              {hints.map((h) => (
                <div
                  key={h.id}
                  className="flex items-start gap-2 px-3 py-2 rounded-md bg-muted/50 text-xs"
                >
                  <span className="text-[10px] text-muted-foreground bg-muted px-1.5 py-0.5 rounded shrink-0">
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
        <div className="flex items-center gap-2 px-4 py-3 shrink-0">
          {isInferred && edge.humanState !== "CONFIRMED" && (
            <button
              className="flex items-center gap-1 text-xs px-2.5 py-1.5 rounded-md
                         bg-primary text-primary-foreground hover:bg-primary/90
                         transition-colors disabled:opacity-50"
              disabled={acting != null}
              onClick={() => runAction("CONFIRM")}
            >
              <HugeiconsIcon icon={Tick01Icon} className="size-3.5" />
              {t("actionConfirm")}
            </button>
          )}
          <button
            className="flex items-center gap-1 text-xs px-2.5 py-1.5 rounded-md
                       bg-destructive/10 text-destructive hover:bg-destructive/20
                       transition-colors disabled:opacity-50"
            disabled={acting != null}
            onClick={() => runAction("REMOVE")}
          >
            <HugeiconsIcon icon={Delete02Icon} className="size-3.5" />
            {t("actionRemove")}
          </button>
          {activeCorrection && (
            <button
              className="flex items-center gap-1 text-xs px-2.5 py-1.5 rounded-md
                         bg-muted text-muted-foreground hover:bg-muted/70
                         transition-colors disabled:opacity-50"
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
