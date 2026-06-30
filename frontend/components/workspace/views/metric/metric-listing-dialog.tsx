"use client"

/**
 * 指标上架 Dialog（029 US2）。
 * - 拉 GET /api/metrics（既有指标定义卡片）做 Select,不复制口径,只引用 metricId;
 * - metricType/description/freshnessInfo 为市场元信息。
 * 提交经父级 onSubmit(返回是否关闭);三态/错误提示由父级 resolveGate 处理。
 */

import { useEffect, useState } from "react"
import { useTranslations } from "next-intl"
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogFooter,
  DialogTitle,
} from "@/components/ui/dialog"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { DropdownSelect } from "@/components/ui/select"
import { fetchMetricCards, type MetricCardView } from "@/lib/catalog-api"
import { buildListingPayload, type ListingForm } from "@/lib/metric-listing"

const METRIC_TYPES = ["ATOMIC", "DERIVED", "COMPOSITE"]

export interface MetricListingDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** 返回 true 关闭 dialog（已执行/待审批）;false 保持打开（失败）。 */
  onSubmit: (payload: Record<string, unknown>) => Promise<boolean>
}

export function MetricListingDialog({ open, onOpenChange, onSubmit }: MetricListingDialogProps) {
  const t = useTranslations("metricMarketplace")
  const tc = useTranslations("common")
  const [cards, setCards] = useState<MetricCardView[]>([])
  const [metricId, setMetricId] = useState("")
  const [form, setForm] = useState<ListingForm>({ metricType: "ATOMIC", description: "", freshnessInfo: "" })
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!open) return
    setError(null)
    setBusy(false)
    setMetricId("")
    setForm({ metricType: "ATOMIC", description: "", freshnessInfo: "" })
    void fetchMetricCards().then((r) => setCards(r.data ?? [])).catch(() => setCards([]))
  }, [open])

  const submit = async () => {
    const card = cards.find((c) => String(c.id) === metricId) ?? null
    const payload = buildListingPayload(card, form)
    if (!payload) {
      setError(t("listMetricRequired"))
      return
    }
    setBusy(true)
    setError(null)
    try {
      const close = await onSubmit(payload as unknown as Record<string, unknown>)
      if (close) onOpenChange(false)
    } finally {
      setBusy(false)
    }
  }

  const metricOptions = cards.map((c) => ({ value: String(c.id), label: `${c.code} · ${c.name} (#${c.id})` }))
  const typeOptions = METRIC_TYPES.map((v) => ({ value: v, label: v }))

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>{t("listTitle")}</DialogTitle>
        </DialogHeader>

        <div className="flex flex-col gap-3">
          <Field label={t("listMetric")}>
            <DropdownSelect
              value={metricId}
              onChange={setMetricId}
              options={metricOptions}
              placeholder={t("listMetricPlaceholder")}
              triggerClassName="h-9"
            />
          </Field>
          <Field label={t("metricType")}>
            <DropdownSelect
              value={form.metricType}
              onChange={(v) => setForm((s) => ({ ...s, metricType: v }))}
              options={typeOptions}
              triggerClassName="h-9"
            />
          </Field>
          <Field label={t("listDescription")}>
            <Input value={form.description} onChange={(e) => setForm((s) => ({ ...s, description: e.target.value }))} />
          </Field>
          <Field label={t("freshness")}>
            <Input
              value={form.freshnessInfo}
              onChange={(e) => setForm((s) => ({ ...s, freshnessInfo: e.target.value }))}
              placeholder="T+1"
            />
          </Field>

          {error && <div className="text-sm text-destructive">{error}</div>}
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={busy}>
            {tc("cancel")}
          </Button>
          <Button onClick={submit} disabled={busy}>
            {t("listAction")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="flex flex-col gap-1">
      <span className="text-xs font-medium text-muted-foreground">{label}</span>
      {children}
    </label>
  )
}
