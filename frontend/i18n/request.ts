import { getRequestConfig } from "next-intl/server"
import { defaultLocale, locales, type Locale } from "./locale"

/**
 * next-intl 配置：cookie `NEXT_LOCALE` → Accept-Language → 默认中文。
 *
 * 不走路由前缀（non-routed，design D2），URL 结构保持不变；
 * 与 `redirect("/?open=<view>")` 深链兜底完全兼容。
 *
 * 必须同时返回 locale **与** messages —— 否则 `getMessages()` 取空、
 * `NextIntlClientProvider` 报 "No messages found"，整个 app 500。
 */
export default getRequestConfig(async () => {
  const locale = await resolveLocale()
  return {
    locale,
    messages: (await import(`../messages/${locale}.json`)).default,
  }
})

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
