"use client"

import * as React from "react"
import { useTheme } from "next-themes"
import { useLocale, useTranslations } from "next-intl"
import { useRouter } from "next/navigation"
import { Dialog } from "@base-ui/react/dialog"
import { HugeiconsIcon } from "@hugeicons/react"
import { Settings01Icon } from "@hugeicons/core-free-icons"
import { XIcon } from "lucide-react"

import { cn } from "@/lib/utils"
import { Button } from "@/components/ui/button"

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
    <Button
      variant={active ? "default" : "outline"}
      size="sm"
      onClick={onClick}
      className="flex-1"
    >
      {children}
    </Button>
  )
}

export function SettingsTrigger() {
  const t = useTranslations()
  const { theme, setTheme } = useTheme()
  const locale = useLocale()
  const router = useRouter()

  const themeOptions = [
    { value: "light", label: t("settings.themeLight") },
    { value: "dark", label: t("settings.themeDark") },
    { value: "system", label: t("settings.themeSystem") },
  ]

  const langOptions = [
    { value: "zh-CN", label: t("settings.langZh") },
    { value: "en-US", label: t("settings.langEn") },
  ]

  // 写 cookie `NEXT_LOCALE`（i18n/request.ts 据此选 bundle）+ router.refresh() 重渲染
  // server components（layout 重新 getLocale/getMessages），界面语言即时切换、刷新后保持。
  function changeLocale(next: string) {
    if (next === locale) return
    document.cookie = `NEXT_LOCALE=${next}; path=/; max-age=31536000; samesite=lax`
    router.refresh()
  }

  return (
    <Dialog.Root>
      <Dialog.Trigger
        render={
          <Button
            variant="ghost"
            size="icon"
            className="size-7 text-muted-foreground"
            aria-label={t("settings.open")}
          />
        }
      >
        <HugeiconsIcon icon={Settings01Icon} className="size-4" />
      </Dialog.Trigger>
      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 z-50 bg-foreground/20 transition-opacity duration-150 data-ending-style:opacity-0 data-starting-style:opacity-0 supports-backdrop-filter:backdrop-blur-sm" />
        <Dialog.Popup
          className={cn(
            "fixed left-1/2 top-1/2 z-50 -translate-x-1/2 -translate-y-1/2",
            "w-[90vw] max-w-[420px] rounded-[var(--radius-lg)] border bg-sidebar p-6 shadow-xl",
            "transition duration-200 ease-in-out",
            "data-ending-style:opacity-0 data-starting-style:opacity-0",
            "data-ending-style:scale-95 data-starting-style:scale-100",
          )}
        >
          <Dialog.Title className="font-heading text-base font-medium">
            {t("settings.title")}
          </Dialog.Title>
          <Dialog.Description className="mt-1 text-sm text-muted-foreground">
            {t("settings.description")}
          </Dialog.Description>

          <div className="mt-6 flex flex-col gap-6">
            {/* 外观 */}
            <section className="flex flex-col gap-3">
              <h3 className="text-sm font-medium">{t("settings.appearance")}</h3>
              <div className="flex gap-2">
                {themeOptions.map((opt) => (
                  <SegmentedButton
                    key={opt.value}
                    active={theme === opt.value}
                    onClick={() => setTheme(opt.value)}
                  >
                    {opt.label}
                  </SegmentedButton>
                ))}
              </div>
            </section>

            {/* 语言（界面语言切换：写 cookie + 实时重渲染） */}
            <section className="flex flex-col gap-3">
              <h3 className="text-sm font-medium">{t("settings.language")}</h3>
              <div className="flex flex-col gap-1.5">
                <span className="text-xs text-muted-foreground">{t("settings.uiLanguage")}</span>
                <div className="flex gap-2">
                  {langOptions.map((opt) => (
                    <SegmentedButton
                      key={opt.value}
                      active={locale === opt.value}
                      onClick={() => changeLocale(opt.value)}
                    >
                      {opt.label}
                    </SegmentedButton>
                  ))}
                </div>
              </div>
            </section>
          </div>

          {/* 关闭按钮 */}
          <Dialog.Close
            render={
              <Button
                variant="ghost"
                size="icon"
                className="absolute right-3 top-3 size-7"
                aria-label={t("settings.close")}
              />
            }
          >
            <XIcon className="size-4" />
          </Dialog.Close>
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
