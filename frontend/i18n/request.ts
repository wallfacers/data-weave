import { getRequestConfig } from "next-intl/server"
import { defaultLocale, locales, type Locale } from "./locale"

type Messages = Record<string, unknown>

/**
 * next-intl 配置：cookie `NEXT_LOCALE` → Accept-Language → 默认中文。
 *
 * 不走路由前缀（non-routed，design D2），URL 结构保持不变；
 * 与 `redirect("/?open=<view>")` 深链兜底完全兼容。
 *
 * 必须同时返回 locale **与** messages —— 否则 `getMessages()` 取空、
 * `NextIntlClientProvider` 报 "No messages found"，整个 app 500。
 *
 * 非默认 locale 以 zh-CN 为基底深合并（design 10.4）：任何 en-US 缺失的 key
 * 自动 fallback 到 zh-CN 文案，不暴露裸 key。日常键集严格对齐（CI 校验），
 * 此为兜底防御。
 */
export default getRequestConfig(async () => {
  const locale = await resolveLocale()
  const messages = (await import(`../messages/${locale}.json`)).default as Messages
  if (locale === defaultLocale) {
    return { locale, messages }
  }
  const base = (await import(`../messages/${defaultLocale}.json`)).default as Messages
  return { locale, messages: deepMerge(base, messages) }
})

/** 深合并：以 base 为底，override 覆盖；嵌套对象逐层合并，标量直接覆盖。 */
function deepMerge(base: Messages, override: Messages): Messages {
  const out: Messages = { ...base }
  for (const [k, v] of Object.entries(override)) {
    const b = out[k]
    if (isObject(b) && isObject(v)) {
      out[k] = deepMerge(b, v)
    } else {
      out[k] = v
    }
  }
  return out
}

function isObject(v: unknown): v is Messages {
  return typeof v === "object" && v !== null && !Array.isArray(v)
}

/** cookie `NEXT_LOCALE` → `Accept-Language`（en/zh 前缀）→ 默认 zh-CN。 */
async function resolveLocale(): Promise<Locale> {
  const { cookies, headers } = await import("next/headers")
  const cookieStore = await cookies()
  const cookieLocale = cookieStore.get("NEXT_LOCALE")?.value as Locale | undefined
  if (cookieLocale && locales.includes(cookieLocale)) {
    return cookieLocale
  }
  const h = await headers()
  const accept = h.get("accept-language")
  if (accept) {
    const preferred = accept
      .split(",")
      .map((p: string) => {
        const [tag, q] = p.trim().split(";q=")
        return { tag: tag.trim().toLowerCase(), q: q ? parseFloat(q) : 1 }
      })
      .sort((a: { q: number }, b: { q: number }) => b.q - a.q)
    for (const { tag } of preferred) {
      if (tag.startsWith("en")) return "en-US"
      if (tag.startsWith("zh")) return "zh-CN"
    }
  }
  return defaultLocale
}
