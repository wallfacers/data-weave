import { defaultLocale, type Locale } from "@/i18n/locale"

/**
 * 客户端读取当前 UI locale：优先 cookie `NEXT_LOCALE`，回退默认。
 *
 * 用于在 fetch / HttpAgent 等非 next-intl hook 上下文注入 `Accept-Language`。
 * 服务端场景请用 `getLocale()` from `next-intl/server`。
 */
export function getClientLocale(): Locale {
  if (typeof document === "undefined") return defaultLocale
  const match = document.cookie.match(/(?:^|;\s*)NEXT_LOCALE=([^;]+)/)
  const tag = match?.[1]
  if (tag === "en-US" || tag === "zh-CN") return tag
  return defaultLocale
}

/** 客户端 locale → Accept-Language 头值。 */
export function acceptLanguageHeader(): string {
  return getClientLocale()
}

/**
 * 写 cookie `NEXT_LOCALE`（`i18n/request.ts` 据此选 bundle）。
 * 调用方需自行 `router.refresh()` 触发 server components 重渲染。
 */
export function setClientLocale(locale: Locale): void {
  document.cookie = `NEXT_LOCALE=${locale}; path=/; max-age=31536000; samesite=lax`
}
