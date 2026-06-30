"use client"

/**
 * 指标复用 Dialog（029 US2）。替换原 `window.prompt`。
 * - consumerType Select(METRIC/TASK/ASSET) + consumerRef 输入;
 * - 提交经父级 onSubmit;`catalog.reuse_cycle` 由父级映射为「会形成循环依赖」专门提示(FR-007)。
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

const CONSUMER_TYPES = ["METRIC", "TASK", "ASSET"]

export interface MetricReuseDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** 返回 true 关闭 dialog（已执行/待审批）;false 保持打开（失败）。 */
  onSubmit: (body: { consumerType: string; consumerRef: string }) => Promise<boolean>
}

export function MetricReuseDialog({ open, onOpenChange, onSubmit }: MetricReuseDialogProps) {
  const t = useTranslations("metricMarketplace")
  const tc = useTranslations("common")
  const [consumerType, setConsumerType] = useState("METRIC")
  const [consumerRef, setConsumerRef] = useState("")
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!open) return
    setError(null)
    setBusy(false)
    setConsumerType("METRIC")
    setConsumerRef("")
  }, [open])

  const submit = async () => {
    if (consumerRef.trim() === "") {
      setError(t("reuseRefRequired"))
      return
    }
    setBusy(true)
    setError(null)
    try {
      const close = await onSubmit({ consumerType, consumerRef: consumerRef.trim() })
      if (close) onOpenChange(false)
    } finally {
      setBusy(false)
    }
  }

  const typeOptions = CONSUMER_TYPES.map((v) => ({ value: v, label: v }))

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>{t("reuseTitle")}</DialogTitle>
        </DialogHeader>

        <div className="flex flex-col gap-3">
          <Field label={t("consumerType")}>
            <DropdownSelect value={consumerType} onChange={setConsumerType} options={typeOptions} triggerClassName="h-9" />
          </Field>
          <Field label={t("consumerRef")}>
            <Input
              value={consumerRef}
              onChange={(e) => setConsumerRef(e.target.value)}
              placeholder={t("consumerRefPlaceholder")}
            />
          </Field>

          {error && <div className="text-sm text-destructive">{error}</div>}
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={busy}>
            {tc("cancel")}
          </Button>
          <Button onClick={submit} disabled={busy}>
            {t("reuse")}
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
