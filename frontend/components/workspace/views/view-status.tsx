"use client"

import { useTranslations } from "next-intl"

import { API_BASE } from "@/lib/types"

/** 视图统一的加载/失败占位 */
export function ViewStatus({ loading }: { loading: boolean }) {
  const t = useTranslations("viewStatus")
  return (
    <div className="flex flex-1 items-center justify-center p-10 text-center">
      <p className="text-sm text-muted-foreground">
        {loading ? t("loading") : t("connectError", { apiBase: API_BASE })}
      </p>
    </div>
  )
}
