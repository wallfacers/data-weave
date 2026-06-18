"use client"

import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import { ArrowLeft01Icon } from "@hugeicons/core-free-icons"

/** 未落地模块的占位视图：操作统一走左侧 Agent 对话 */
export function PlaceholderView({
  title,
  description,
}: {
  title: string
  description: string
}) {
  const t = useTranslations("placeholderView")
  return (
    <div className="flex flex-1 flex-col items-center justify-center gap-4 p-10 text-center">
      <h1 className="text-2xl font-semibold tracking-tight">{title}</h1>
      <p className="max-w-md text-muted-foreground">{description}</p>
      <span className="inline-flex items-center gap-1 text-sm text-primary">
        <HugeiconsIcon icon={ArrowLeft01Icon} className="size-4" />
        {t("agentHint")}
      </span>
    </div>
  )
}
