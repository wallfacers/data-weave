/**
 * cron 表达式 → 简短可读形式（仅处理标准 6 段 Quartz cron）。
 *
 * 翻译文案走调用方传入的 t（next-intl ops 命名空间的 cronDaily / cronWeekday / cronMonthly），
 * 故本 util 是纯函数、不直接依赖 next-intl，可在多个面板复用。
 */
export type CronTranslator = (key: string, params?: Record<string, unknown>) => string

export function humanizeCron(
  cron: string | null | undefined,
  t: CronTranslator,
): string {
  if (!cron) return "—"
  const parts = cron.trim().split(/\s+/)
  if (parts.length < 6) return cron
  const [sec, min, hour, dom, , dow] = parts
  const hh = hour.padStart(2, "0")
  const mm = min.padStart(2, "0")
  if (sec === "0" && dom === "*" && dow === "?") {
    return t("cronDaily", { time: min === "0" ? `${hh}:00` : `${hh}:${mm}` })
  }
  if (sec === "0" && dom === "*" && dow === "1-5") {
    return t("cronWeekday", { time: `${hh}:${mm}` })
  }
  if (sec === "0" && min === "0" && dom !== "*" && dow === "?") {
    return t("cronMonthly", { day: dom, time: `${hh}:00` })
  }
  return cron
}
