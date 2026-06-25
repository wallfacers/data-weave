"use client"

/**
 * 补数据弹窗：目标 + 日期区间 + includeDownstream + parallelism。
 * 提交 POST /api/ops/backfill，按 outcome 三态分流。
 */

import { useMemo, useState } from "react"
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
import { DropdownSelect } from "@/components/ui/select"
import { Checkbox } from "@/components/ui/checkbox"
import { authFetch, API_BASE, type ApiResponse } from "@/lib/types"

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

interface BackfillDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  onSuccess?: () => void
  /** 预填的目标类型（例如从举手台 "backfill" 动作传入） */
  initialTargetType?: "task" | "workflow"
  initialTargetId?: number | string
}

export function BackfillDialog({
  open,
  onOpenChange,
  onSuccess,
  initialTargetType = "task",
  initialTargetId = "",
}: BackfillDialogProps) {
  const t = useTranslations("ops")
  const locale = useLocale()
  const dateFnsLocale = useMemo(() => (locale === "zh-CN" ? zhCN : enUS), [locale])
  const [targetType, setTargetType] = useState<"task" | "workflow">(initialTargetType)
  const [targetId, setTargetId] = useState<string>(String(initialTargetId ?? ""))
  const [dateStart, setDateStart] = useState("")
  const [dateEnd, setDateEnd] = useState("")
  const [includeDownstream, setIncludeDownstream] = useState(false)
  const [parallelism, setParallelism] = useState(3)
  const [busy, setBusy] = useState(false)

  function reset() {
    setTargetType("task")
    setTargetId("")
    setDateStart("")
    setDateEnd("")
    setIncludeDownstream(false)
    setParallelism(3)
  }

  async function submit() {
    if (!targetId || !dateStart || !dateEnd) {
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
          targetType,
          targetId: Number(targetId),
          dateStart,
          dateEnd,
          includeDownstream,
          parallelism,
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
              {t("backfillTargetType")}
            </label>
            <DropdownSelect
              value={targetType}
              onChange={(v) => setTargetType(v as "task" | "workflow")}
              triggerClassName="w-auto"
              options={[
                { value: "task", label: t("backfillTargetTypeTask") },
                { value: "workflow", label: t("backfillTargetTypeWorkflow") },
              ]}
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <label className="text-xs font-medium text-muted-foreground">
              {t("backfillTargetId")}
            </label>
            <Input
              type="number"
              value={targetId}
              onChange={(e) => setTargetId(e.target.value)}
              placeholder={t("backfillTargetIdPh")}
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
          <label className="flex items-center gap-2 text-sm">
            <Checkbox checked={includeDownstream} onChange={setIncludeDownstream} />
            {t("backfillIncludeDownstream")}
          </label>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={busy}>
            {t("backfillCancel")}
          </Button>
          <Button onClick={submit} disabled={busy}>
            {busy ? "Submitting" : t("backfillSubmit")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
