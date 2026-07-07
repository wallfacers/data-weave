"use client"

/**
 * 血缘边样式图例（画布左下角悬浮卡片，FR-016）。
 *
 * 解释三种主要边编码：
 *   —— 已确认 / 高亮 / 默认（实线） → transparent line mock
 *   - - - 推断 / 未验证（虚线） → legendInferred
 *   高亮（primary 色、加粗、animated） → 选中/影响/路径
 *
 * 设计约束（DESIGN.md）：语义 token、pointer-events-auto 可交互、不手写 dark:。
 */
import { useTranslations } from "next-intl"

export function LineageLegend() {
  const t = useTranslations("lineageView")

  return (
    <div className="pointer-events-none absolute bottom-3 left-3">
      <div className="pointer-events-auto rounded-md border bg-card/90 px-3 py-2 text-[11px] shadow-sm backdrop-blur-sm">
        <div className="flex flex-col gap-1.5">
          {/* 已确认 */}
          <div className="flex items-center gap-2">
            <svg width="24" height="4" aria-hidden>
              <line x1="0" y1="2" x2="24" y2="2" stroke="var(--color-success)" strokeWidth="1.75" />
            </svg>
            <span>{t("legendConfirmed")}</span>
          </div>

          {/* 推断（虚线） */}
          <div className="flex items-center gap-2">
            <svg width="24" height="4" aria-hidden>
              <line x1="0" y1="2" x2="24" y2="2" stroke="var(--color-border)" strokeWidth="1.25" strokeDasharray="5 3" />
            </svg>
            <span>{t("legendInferred")}</span>
          </div>

          {/* 高亮（选中/影响/路径） */}
          <div className="flex items-center gap-2">
            <svg width="24" height="4" aria-hidden>
              <line x1="0" y1="2" x2="24" y2="2" stroke="var(--color-primary)" strokeWidth="2.5" />
            </svg>
            <span>{t("legendHighlight")}</span>
          </div>

          {/* 默认 */}
          <div className="flex items-center gap-2">
            <svg width="24" height="4" aria-hidden>
              <line x1="0" y1="2" x2="24" y2="2" stroke="var(--color-border)" strokeWidth="1.25" />
            </svg>
            <span>{t("legendDefault")}</span>
          </div>
        </div>
      </div>
    </div>
  )
}
