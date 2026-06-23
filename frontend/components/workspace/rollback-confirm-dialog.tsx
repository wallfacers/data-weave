"use client"

/**
 * 回滚确认弹窗：告知用户当前草稿将被覆盖，确认后调用 rollback API。
 */
import { useTranslations } from "next-intl"
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from "@/components/ui/dialog"
import { Button } from "@/components/ui/button"
import type { TaskDefVersion } from "@/lib/types"

export interface RollbackConfirmDialogProps {
  open: boolean
  onClose: () => void
  onConfirm: () => void
  version: TaskDefVersion | null
  hasDraft: boolean
  rolling: boolean
}

export function RollbackConfirmDialog({
  open,
  onClose,
  onConfirm,
  version,
  hasDraft,
  rolling,
}: RollbackConfirmDialogProps) {
  const t = useTranslations()

  if (!version) return null

  return (
    <Dialog open={open} onOpenChange={(o) => { if (!o) onClose() }}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t("versionHistory.rollbackConfirmTitle")}</DialogTitle>
          <DialogDescription>
            {hasDraft
              ? t("versionHistory.rollbackConfirmWithDraft", {
                  vno: version.versionNo,
                  remark: version.remark ?? "-",
                })
              : t("versionHistory.rollbackConfirm", {
                  vno: version.versionNo,
                  remark: version.remark ?? "-",
                })}
          </DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <Button variant="outline" size="sm" onClick={onClose} disabled={rolling}>
            {t("common.cancel")}
          </Button>
          <Button variant="destructive" size="sm" onClick={onConfirm} disabled={rolling}>
            {rolling ? t("versionHistory.rolling") : t("versionHistory.confirmRollback")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
