import { defineRouting } from "next-intl/routing"
import { defaultLocale, locales } from "./locale"

/**
 * 路由配置：禁用前缀 + 禁用域，走 cookie 模式（design D2）。
 * URL 结构保持现状，与 `redirect("/?open=<view>")` 深链完全兼容。
 */
export const routing = defineRouting({
  locales,
  defaultLocale,
  localePrefix: "never",
  localeDetection: false,
})
