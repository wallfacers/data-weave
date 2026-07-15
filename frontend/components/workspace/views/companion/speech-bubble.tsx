"use client"

/**
 * 虚拟管家播报字幕气泡。
 *
 * - 居中悬浮于虚拟人上方
 * - 淡入/淡出动画（show prop 控制）
 * - 内容由父组件管理（SSE 播报/模拟）
 */
import { useTranslations } from "next-intl"

interface SpeechBubbleProps {
  text: string | null
}

export function SpeechBubble({ text }: SpeechBubbleProps) {
  const t = useTranslations("companion")

  if (!text) return null

  return (
    <div
      className={`mx-auto max-w-[520px] rounded-2xl border border-border/50 bg-card/70 px-4 py-3 text-sm leading-relaxed text-foreground backdrop-blur-md shadow-[0_8px_32px_rgba(0,0,0,.15)] transition-all duration-350 ${
        text ? "translate-y-[-6px] opacity-100" : "translate-y-0 opacity-0"
      }`}
    >
      <span className="mb-0.5 block text-xs text-primary">{t("name")}</span>
      {text}
    </div>
  )
}
