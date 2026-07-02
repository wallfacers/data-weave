"use client"

/**
 * 补数据弹窗：目标(可搜索选择器) + 日期区间 + parallelism。
 * 提交 POST /api/ops/backfill，按 outcome 三态分流。
 */

import { useEffect, useMemo, useState } from "react"
import { useLocale, useTranslations } from "next-intl"
import { zhCN, enUS } from "date-fns/locale"
import { toast } from "sonner"

import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from "@/components/ui/dialog"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { DatePicker } from "@/components/ui/date-picker"
import { Checkbox } from "@/components/ui/checkbox"
import { authFetch, API_BASE, type ApiResponse } from "@/lib/types"
import {
  TargetSearchSelect,
  buildCatalogPathMap,
  type TargetOption,
} from "./target-search-select"

interface BackfillResponse {
  id: string
  targetType: string
  targetId: number
  targetName?: string
  dateStart: string
  dateEnd: string
  parallelism: number
  state: string
  total: number
}

interface BackfillSubmitResponse {
  code: number
  data: BackfillResponse | null
  outcome: "EXECUTED" | "PENDING_APPROVAL" | "REJECTED"
  message?: string
}

interface DownstreamTask {
  id: number
  name: string
  catalogNodeId: number | null
  level: number
}

interface BackfillDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  onSuccess?: () => void
  /** 预填的目标类型（例如从举手台 "backfill" 动作 / 就地入口传入） */
  initialTargetType?: "task" | "workflow"
  initialTargetId?: number | string
  /** 预填目标的显示名（就地入口传入，避免显示空名） */
  initialTargetName?: string
}

export function BackfillDialog({
  open,
  onOpenChange,
  onSuccess,
  initialTargetType = "task",
  initialTargetId = "",
  initialTargetName,
}: BackfillDialogProps) {
  const t = useTranslations("ops")
  const locale = useLocale()
  const dateFnsLocale = useMemo(() => (locale === "zh-CN" ? zhCN : enUS), [locale])
  const [target, setTarget] = useState<TargetOption | null>(
    initialTargetId !== "" && initialTargetId != null
      ? { id: Number(initialTargetId), name: initialTargetName ?? `#${initialTargetId}`, type: initialTargetType }
      : null,
  )
  const [dateStart, setDateStart] = useState("")
  const [dateEnd, setDateEnd] = useState("")
  const [parallelism, setParallelism] = useState(3)
  const [busy, setBusy] = useState(false)
  const [pathMap, setPathMap] = useState<Map<number, string>>(new Map())
  const [downstream, setDownstream] = useState<DownstreamTask[]>([])
  const [downstreamLoading, setDownstreamLoading] = useState(false)
  const [selectedDownstream, setSelectedDownstream] = useState<Set<number>>(new Set())

  // 打开时同步预填目标(就地入口每次打开可能预填不同目标)
  useEffect(() => {
    if (!open) return
    setTarget(
      initialTargetId !== "" && initialTargetId != null
        ? { id: Number(initialTargetId), name: initialTargetName ?? `#${initialTargetId}`, type: initialTargetType }
        : null,
    )
  }, [open, initialTargetType, initialTargetId, initialTargetName])

  // 加载类目树一次,构建 catalogNodeId → 路径(供选择器候选项展示路径)
  useEffect(() => {
    if (!open) return
    authFetch(`${API_BASE}/api/catalog/tree`)
      .then((r) => (r.ok ? (r.json() as Promise<ApiResponse<unknown>>) : Promise.resolve(null)))
      .then((json) => {
        if (!json || json.code !== 0 || !json.data) return
        const roots = (json.data as Record<string, unknown>).roots
        if (Array.isArray(roots)) {
          setPathMap(buildCatalogPathMap(roots as Parameters<typeof buildCatalogPathMap>[0]))
        }
      })
      .catch(() => {})
  }, [open])

  // 选定 task 目标后拉取血缘下游预览(默认不全选,避免误炸全图);workflow 目标 M1 维持整 DAG,不展示下游树。
  useEffect(() => {
    setDownstream([])
    setSelectedDownstream(new Set())
    if (!open || target?.type !== "task" || !target) return
    let cancelled = false
    setDownstreamLoading(true)
    authFetch(`${API_BASE}/api/ops/backfill/downstream-preview?targetType=task&targetId=${target.id}`)
      .then((r) => (r.ok ? (r.json() as Promise<ApiResponse<DownstreamTask[]>>) : Promise.resolve(null)))
      .then((json) => {
        if (cancelled || !json || json.code !== 0) return
        setDownstream(Array.isArray(json.data) ? json.data : [])
      })
      .catch(() => {})
      .finally(() => {
        if (!cancelled) setDownstreamLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [open, target?.type, target])

  function toggleDownstream(id: number) {
    setSelectedDownstream((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  function reset() {
    setTarget(null)
    setDateStart("")
    setDateEnd("")
    setParallelism(3)
    setDownstream([])
    setSelectedDownstream(new Set())
  }

  async function submit() {
    if (!target || !dateStart || !dateEnd) {
      toast.error(t("backfillValidationError"))
      return
    }
    if (dateStart > dateEnd) {
      toast.error(t("backfillValidationError"))
      return
    }
    setBusy(true)
    try {
      const res = await authFetch(`${API_BASE}/api/ops/backfill`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          targetType: target.type,
          targetId: target.id,
          dateStart,
          dateEnd,
          includeDownstream: selectedDownstream.size > 0,
          parallelism,
          downstreamTaskIds: target.type === "task" ? Array.from(selectedDownstream) : [],
        }),
      })
      const json = (await res.json().catch(() => null)) as BackfillSubmitResponse | null
      if (!json || json.code !== 0) {
        toast.error(t("actionFailed", { label: t("backfillTitle"), msg: json?.message ?? `HTTP ${res.status}` }))
        return
      }
      // ★ 按 outcome 三态分流
      if (json.outcome === "PENDING_APPROVAL") {
        toast.info(`${t("backfillTitle")} · ${t("outcomePendingApproval")}`)
      } else if (json.outcome === "REJECTED") {
        toast.error(`${t("backfillTitle")} · ${t("outcomeRejected")}`)
      } else {
        toast.success(`${t("backfillTitle")} · ${t("outcomeExecuted")}`)
        onSuccess?.()
        reset()
        onOpenChange(false)
      }
    } catch (e) {
      toast.error(t("actionFailed", { label: t("backfillTitle"), msg: e instanceof Error ? e.message : t("networkError") }))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{t("backfillTitle")}</DialogTitle>
          <DialogDescription>{t("backfillSubtitle")}</DialogDescription>
        </DialogHeader>
        <div className="flex flex-col gap-3">
          <div className="flex flex-col gap-1.5">
            <label className="text-xs font-medium text-muted-foreground">
              {t("backfillTarget")}
            </label>
            <TargetSearchSelect
              value={target}
              onChange={setTarget}
              pathMap={pathMap}
            />
          </div>
          <div className="grid grid-cols-2 gap-2">
            <div className="flex flex-col gap-1.5">
              <label className="text-xs font-medium text-muted-foreground">
                {t("backfillDateStart")}
              </label>
              <DatePicker
                value={dateStart || undefined}
                onChange={setDateStart}
                placeholder={t("backfillDateStart")}
                locale={dateFnsLocale}
                quickLabels={{ today: t("quickToday"), yesterday: t("quickYesterday") }}
                disableClear
              />
            </div>
            <div className="flex flex-col gap-1.5">
              <label className="text-xs font-medium text-muted-foreground">
                {t("backfillDateEnd")}
              </label>
              <DatePicker
                value={dateEnd || undefined}
                onChange={setDateEnd}
                placeholder={t("backfillDateEnd")}
                locale={dateFnsLocale}
                quickLabels={{ today: t("quickToday"), yesterday: t("quickYesterday") }}
                disableClear
              />
            </div>
          </div>
          <div className="flex flex-col gap-1.5">
            <label className="text-xs font-medium text-muted-foreground">
              {t("backfillParallelism")}
            </label>
            <Input
              type="number"
              min={1}
              max={10}
              value={parallelism}
              onChange={(e) => setParallelism(Number(e.target.value) || 1)}
            />
          </div>
          {/* 下游影响范围:仅 task 目标;默认不全选,勾选具体补哪些 */}
          {target?.type === "task" && target && (
            <div className="flex flex-col gap-1.5">
              <div className="flex items-center justify-between">
                <label className="text-xs font-medium text-muted-foreground">
                  {t("backfillDownstream")}
                </label>
                <span className="text-xs text-muted-foreground">
                  {t("backfillAffected", { n: 1 + selectedDownstream.size })}
                </span>
              </div>
              <p className="text-xs text-muted-foreground/70">{t("backfillDownstreamHint")}</p>
              {downstreamLoading ? (
                <div className="rounded-md border px-2 py-3 text-center text-xs text-muted-foreground">
                  {t("backfillDownstreamLoading")}
                </div>
              ) : downstream.length === 0 ? (
                <div className="rounded-md border px-2 py-3 text-center text-xs text-muted-foreground">
                  {t("backfillDownstreamEmpty")}
                </div>
              ) : (
                <div className="flex max-h-40 flex-col gap-0.5 overflow-y-auto rounded-md border p-1">
                  {downstream.map((d) => (
                    <label
                      key={d.id}
                      className="flex cursor-pointer items-center gap-2 rounded-md px-1.5 py-1 hover:bg-muted"
                    >
                      <Checkbox
                        checked={selectedDownstream.has(d.id)}
                        onChange={() => toggleDownstream(d.id)}
                      />
                      <span className="min-w-0 flex-1 truncate text-sm">{d.name}</span>
                      {d.catalogNodeId != null && pathMap.get(d.catalogNodeId) && (
                        <span className="shrink-0 truncate text-xs text-muted-foreground">
                          {pathMap.get(d.catalogNodeId)}
                        </span>
                      )}
                      <span className="shrink-0 rounded bg-muted px-1 font-mono text-[10px]">
                        L{d.level}
                      </span>
                    </label>
                  ))}
                </div>
              )}
            </div>
          )}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={busy}>
            {t("backfillCancel")}
          </Button>
          <Button onClick={submit} disabled={busy}>
            {busy ? t("backfillSubmitting") : t("backfillSubmit")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
