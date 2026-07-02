/**
 * 相对时间 util —— 把未来/已过的 ISO 时间点格式化为结构化 i18n 信号。
 *
 * 纯函数（无 DOM、无 locale 依赖）：返回 { key, values } 供 next-intl `t()` 用，
 * 文案集中在 messages/{zh-CN,en-US}.json，便于 CI 校两 bundle key 集一致。
 *
 * 主要用途：「下次触发时间」列（spec FR-008）——未来→"N 小时后"，已过期→"已过期 Nm"。
 * 仿 cron-format.ts#humanizeCron 的纯函数先例（research.md D5）。
 */

export type RelativeNextTrigger =
  | { key: "relSoon" }
  | { key: "relInMinutes"; values: { n: number } }
  | { key: "relInHours"; values: { n: number } }
  | { key: "relInDays"; values: { n: number } }
  | { key: "relExpiredMinutes"; values: { n: number } }
  | { key: "relExpiredHours"; values: { n: number } }
  | null

const MIN = 60_000
const HOUR = 60 * MIN
const DAY = 24 * HOUR

/**
 * 计算目标时间相对 now 的展示信号。
 * @param iso 目标时间 ISO 字符串；null/空/无效 → null（调用方显示 —）
 * @param now 当前时间（注入，便于测试；避免组件内直接 new Date() 副作用）
 */
export function relativeNextTrigger(iso: string | null, now: Date): RelativeNextTrigger {
  if (!iso) return null
  const t = Date.parse(iso)
  if (Number.isNaN(t)) return null
  const diff = t - now.getTime() // 正=未来，负=已过期
  const abs = Math.abs(diff)

  if (diff >= 0) {
    // 未来
    if (abs < MIN) return { key: "relSoon" }
    if (abs < HOUR) return { key: "relInMinutes", values: { n: Math.round(abs / MIN) } }
    if (abs < DAY) return { key: "relInHours", values: { n: Math.round(abs / HOUR) } }
    return { key: "relInDays", values: { n: Math.round(abs / DAY) } }
  }
  // 已过期
  if (abs < HOUR) return { key: "relExpiredMinutes", values: { n: Math.max(1, Math.round(abs / MIN)) } }
  return { key: "relExpiredHours", values: { n: Math.round(abs / HOUR) } }
}
