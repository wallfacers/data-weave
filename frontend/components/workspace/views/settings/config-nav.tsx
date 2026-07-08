"use client"

/**
 * 057 系统设置「配置」tab 左侧分区导航。
 *
 * 设计约束（DESIGN.md）：语义 token、无手写分割线（靠留白 + 选中态背景区分）、
 * hugeicons（非 lucide）、DwScroll 接管滚动、键盘可达。
 */
import { useTranslations } from "next-intl"
import { HugeiconsIcon } from "@hugeicons/react"
import { cn } from "@/lib/utils"
import { DwScroll } from "@/components/ui/dw-scroll"
import type { ConfigSection } from "@/lib/workspace/settings/config-sections"

export interface ConfigNavProps {
  sections: ConfigSection[]
  activeId: string
  onSelect: (id: string) => void
}

export function ConfigNav({ sections, activeId, onSelect }: ConfigNavProps) {
  const t = useTranslations()
  return (
    <DwScroll className="flex-1" innerClassName="overflow-y-auto">
      <nav className="flex flex-col gap-0.5 p-2" aria-label="config sections">
        {sections.map((s) => {
          const active = s.id === activeId
          return (
            <button
              key={s.id}
              type="button"
              onClick={() => onSelect(s.id)}
              aria-current={active ? "page" : undefined}
              className={cn(
                "flex items-center gap-2 rounded-md px-2.5 py-2 text-left text-sm transition-colors",
                active
                  ? "bg-muted font-medium text-foreground"
                  : "text-muted-foreground hover:bg-muted/60 hover:text-foreground",
              )}
            >
              <HugeiconsIcon icon={s.icon} className="size-4 shrink-0" />
              <span className="truncate">{t(s.titleKey)}</span>
            </button>
          )
        })}
      </nav>
    </DwScroll>
  )
}
