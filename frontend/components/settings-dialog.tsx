"use client"

import * as React from "react"
import { useTheme } from "next-themes"
import { useLocale, useTranslations } from "next-intl"
import { useRouter } from "next/navigation"

import { Button } from "@/components/ui/button"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { dateFormatStore, DATE_FORMAT_OPTIONS, type DateFormatKey } from "@/lib/date-format-store"
import { useFormatDateTime } from "@/hooks/use-format-date-time"
import { setClientLocale } from "@/lib/locale-client"
import type { Locale } from "@/i18n/locale"

function SegmentedButton({
  active,
  onClick,
  children,
}: {
  active: boolean
  onClick: () => void
  children: React.ReactNode
}) {
  return (
    <Button variant={active ? "default" : "outline"} size="sm" onClick={onClick} className="flex-1">
      {children}
    </Button>
  )
}

/**
 * 个人外观设置弹框——主题 / 界面语言 / 日期时间显示格式。
 * 由 {@link "@/components/workspace/left-nav"} 的用户菜单触发（受控 open）。
 */
export function SettingsDialog({
  open,
  onOpenChange,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const t = useTranslations("settingsDialog")
  const { theme, setTheme } = useTheme()
  const locale = useLocale()
  const router = useRouter()
  const dateFormat = dateFormatStore((s) => s.format)
  const setDateFormat = dateFormatStore((s) => s.setFormat)
  const formatDateTime = useFormatDateTime()

  const themeOptions = [
    { value: "light", label: t("themeLight") },
    { value: "dark", label: t("themeDark") },
    { value: "system", label: t("themeSystem") },
  ]

  const langOptions: Array<{ value: Locale; label: string }> = [
    { value: "zh-CN", label: t("langZh") },
    { value: "en-US", label: t("langEn") },
  ]

  const previewIso = new Date().toISOString()

  // router.refresh() 重渲染 server components，界面语言即时切换、刷新后保持。
  function changeLocale(next: Locale) {
    if (next === locale) return
    setClientLocale(next)
    router.refresh()
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t("title")}</DialogTitle>
          <DialogDescription>{t("description")}</DialogDescription>
        </DialogHeader>

        <div className="flex flex-col gap-6">
          <section className="flex flex-col gap-3">
            <h3 className="text-sm font-medium">{t("appearance")}</h3>
            <div className="flex gap-2">
              {themeOptions.map((opt) => (
                <SegmentedButton key={opt.value} active={theme === opt.value} onClick={() => setTheme(opt.value)}>
                  {opt.label}
                </SegmentedButton>
              ))}
            </div>
          </section>

          <section className="flex flex-col gap-3">
            <h3 className="text-sm font-medium">{t("language")}</h3>
            <div className="flex flex-col gap-1.5">
              <span className="text-xs text-muted-foreground">{t("uiLanguage")}</span>
              <div className="flex gap-2">
                {langOptions.map((opt) => (
                  <SegmentedButton key={opt.value} active={locale === opt.value} onClick={() => changeLocale(opt.value)}>
                    {opt.label}
                  </SegmentedButton>
                ))}
              </div>
            </div>
          </section>

          <section className="flex flex-col gap-3">
            <h3 className="text-sm font-medium">{t("dateTime")}</h3>
            <div className="flex flex-col gap-1.5">
              <span className="text-xs text-muted-foreground">{t("dateTimeFormat")}</span>
              <div className="flex gap-2">
                {DATE_FORMAT_OPTIONS.map((opt) => (
                  <SegmentedButton
                    key={opt.key}
                    active={dateFormat === opt.key}
                    onClick={() => setDateFormat(opt.key as DateFormatKey)}
                  >
                    {t(`dateFormat_${opt.key}` as Parameters<typeof t>[0])}
                  </SegmentedButton>
                ))}
              </div>
              <p className="mt-1 font-mono text-xs text-muted-foreground">{formatDateTime(previewIso)}</p>
            </div>
          </section>
        </div>
      </DialogContent>
    </Dialog>
  )
}
