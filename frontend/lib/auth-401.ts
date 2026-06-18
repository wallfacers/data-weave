/**
 * 全局 401 未授权处理：防抖跳转 + toast 提示。
 * 多个并发请求同时收到 401 时，只会触发一次 toast 和一次跳转。
 */
import { toast } from "sonner"
import { tClient } from "@/lib/i18n-client"

const TOKEN_KEY = "dw.auth.token"
const USER_KEY = "dw.auth.user"

let isHandling401 = false

export function handleUnauthorized(): void {
  if (isHandling401) return
  isHandling401 = true

  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)

  toast.error(tClient("auth.sessionExpiredToast"))

  // 延迟跳转，让用户看到 toast
  setTimeout(() => {
    window.location.href = "/login"
    // 跳转后再等一段时间重置标志，防止极端情况重复触发
    setTimeout(() => {
      isHandling401 = false
    }, 2000)
  }, 800)
}
