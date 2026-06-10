"use client"

import * as React from "react"
import { useTheme } from "next-themes"
import { Dialog } from "@base-ui/react/dialog"
import { HugeiconsIcon } from "@hugeicons/react"
import { Settings01Icon } from "@hugeicons/core-free-icons"
import { XIcon } from "lucide-react"

import { cn } from "@/lib/utils"
import { Button } from "@/components/ui/button"

const THEME_OPTIONS = [
  { value: "light", label: "浅色" },
  { value: "dark", label: "深色" },
  { value: "system", label: "跟随系统" },
] as const

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
  const { theme, setTheme } = useTheme()

  return (
    <Dialog.Root>
      <Dialog.Trigger
        render={
          <Button
            variant="ghost"
            size="icon"
            className="size-7 text-muted-foreground"
            aria-label="项目设置"
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
            项目设置
          </Dialog.Title>
          <Dialog.Description className="mt-1 text-sm text-muted-foreground">
            配置主题外观
          </Dialog.Description>

          <div className="mt-6 flex flex-col gap-6">
            {/* 外观 */}
            <section className="flex flex-col gap-3">
              <h3 className="text-sm font-medium">外观</h3>
              <div className="flex gap-2">
                {THEME_OPTIONS.map((opt) => (
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
          </div>

          {/* 关闭按钮 */}
          <Dialog.Close
            render={
              <Button
                variant="ghost"
                size="icon"
                className="absolute right-3 top-3 size-7"
                aria-label="关闭"
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
