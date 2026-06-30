"use client"

/**
 * 我的订阅 Dialog（029 US3）。
 * - `listSubscriptions` 聚合当前用户全部订阅（ASSET/METRIC）;
 * - 行内退订经 `confirm-dialog` 二次确认 → onUnsubscribe(过闸门,三态如实)。
 * 退订成功后内部重拉清单;父级据 onUnsubscribe 返回刷新资产详情内联订阅态。
 */

import { useCallback, useEffect, useState } from "react"
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import { Delete02Icon } from "@hugeicons/core-free-icons"
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Button } from "@/components/ui/button"
import { ConfirmDialog } from "@/components/workspace/views/shared/confirm-dialog"
import { listSubscriptions, type AssetSubscription } from "@/lib/catalog-api"

export interface SubscriptionsDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** 退订（过闸门）。返回 true=已执行/待审批（应刷新清单）;false=失败。 */
  onUnsubscribe: (subId: number) => Promise<boolean>
}

export function SubscriptionsDialog({ open, onOpenChange, onUnsubscribe }: SubscriptionsDialogProps) {
  const t = useTranslations("assetCatalog")
  const [list, setList] = useState<AssetSubscription[]>([])
  const [loading, setLoading] = useState(false)
  const [confirmSub, setConfirmSub] = useState<AssetSubscription | null>(null)
  const [busy, setBusy] = useState(false)

  const reload = useCallback(async () => {
    setLoading(true)
    try {
      const res = await listSubscriptions()
      setList(res.data ?? [])
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    if (open) void reload()
  }, [open, reload])

  const doUnsubscribe = useCallback(async () => {
    if (!confirmSub) return
    setBusy(true)
    const ok = await onUnsubscribe(confirmSub.id)
    setBusy(false)
    setConfirmSub(null)
    if (ok) void reload()
  }, [confirmSub, onUnsubscribe, reload])

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>{t("mySubscriptions")}</DialogTitle>
        </DialogHeader>

        <div className="flex max-h-96 flex-col gap-1 overflow-auto">
          {loading && <div className="p-4 text-center text-sm text-muted-foreground">{t("loading")}</div>}
          {!loading && list.length === 0 && (
            <div className="p-6 text-center text-sm text-muted-foreground">{t("noSubscriptions")}</div>
          )}
          {!loading &&
            list.map((s) => (
              <div key={s.id} className="flex items-center gap-3 rounded-md border border-border px-3 py-2">
                <div className="min-w-0 flex-1">
                  <div className="text-sm font-medium">{s.targetType} #{s.targetId}</div>
                  {s.changeFilter && <div className="truncate text-xs text-muted-foreground">{s.changeFilter}</div>}
                </div>
                <Button size="sm" variant="outline" onClick={() => setConfirmSub(s)}>
                  <HugeiconsIcon icon={Delete02Icon} className="size-4" />
                  {t("unsubscribeAction")}
                </Button>
              </div>
            ))}
        </div>
      </DialogContent>

      <ConfirmDialog
        open={confirmSub !== null}
        onOpenChange={(o) => !o && setConfirmSub(null)}
        title={t("unsubscribeConfirmTitle")}
        description={t("unsubscribeConfirmDesc")}
        confirmLabel={t("unsubscribeAction")}
        destructive
        busy={busy}
        onConfirm={doUnsubscribe}
      />
    </Dialog>
  )
}
