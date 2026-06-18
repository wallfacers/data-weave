/**
 * 非 React 上下文的轻量 i18n 取词（fetch 拦截、全局 401 等无法用 `useTranslations` hook 的场景）。
 * 从 cookie `NEXT_LOCALE` 读 UI locale，从已打包的 messages bundle 按点路径取词；
 * 缺失时回退 key 本身。React 组件里**一律用 `useTranslations`**，此处仅兜底无 hook 场景。
 */
import zhCN from "@/messages/zh-CN.json"
import enUS from "@/messages/en-US.json"

type Bundle = Record<string, unknown>

function clientLocale(): "zh-CN" | "en-US" {
  if (typeof document === "undefined") return "zh-CN"
  const m = document.cookie.match(/(?:^|;\s*)NEXT_LOCALE=([^;]+)/)
  return m?.[1] === "en-US" ? "en-US" : "zh-CN"
}

/** 按点路径（如 `auth.sessionExpiredToast`）从当前 locale bundle 取词。 */
export function tClient(path: string): string {
  const bundle: Bundle = clientLocale() === "en-US" ? (enUS as Bundle) : (zhCN as Bundle)
  const val = path.split(".").reduce<unknown>((o, k) => {
    if (o && typeof o === "object") return (o as Bundle)[k]
    return undefined
  }, bundle)
  return typeof val === "string" ? val : path
}
