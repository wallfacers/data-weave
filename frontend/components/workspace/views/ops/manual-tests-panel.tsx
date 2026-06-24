"use client"

/**
 * 手动·测试 Tab：M1 占位，提示用户从画布发起手动运行。
 * 待后端契约扩展后再填充真实列表。
 */

import { HugeiconsIcon } from "@hugeicons/react"
import { BoxIcon, Bug01Icon } from "@hugeicons/core-free-icons"
import { useTranslations } from "next-intl"

import { DwScroll } from "@/components/ui/dw-scroll"

export function ManualTestsPanel() {
  const t = useTranslations("ops")
  return (
    <DwScroll className="flex-1" innerClassName="flex flex-col gap-3 p-5">
      <div className="flex items-center gap-2">
        <HugeiconsIcon icon={Bug01Icon} className="size-4 text-primary" />
        <h3 className="text-sm font-semibold tracking-tight">{t("tabManualTests")}</h3>
      </div>

      <div className="flex flex-1 flex-col items-center justify-center gap-3 py-20 text-center">
        <div className="flex size-12 items-center justify-center rounded-xl bg-muted text-muted-foreground">
          <HugeiconsIcon icon={BoxIcon} className="size-6" />
        </div>
        <p className="text-sm text-muted-foreground">{t("manualTestsEmpty")}</p>
        <p className="max-w-sm text-xs text-muted-foreground">{t("manualTestsEmptyHint")}</p>
      </div>
    </DwScroll>
  )
}
